package au.com.shiftyjelly.pocketcasts.musicassistant.client

import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.awaitValue
import au.com.shiftyjelly.pocketcasts.musicassistant.eventually
import au.com.shiftyjelly.pocketcasts.musicassistant.record
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class MaSessionTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer().signedIn()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = MaConnection.pingingClient()
    private val accounts = InMemoryAccountStore(account(server.url))
    private var wallClock = 1_792_000_000.0
    private val session = MaSession(
        scope = scope,
        accounts = accounts,
        connections = { MaConnection(client) },
        retryDelayMs = { 20L },
        wallClock = { wallClock },
    )

    @After
    fun tearDown() {
        session.stop()
        scope.cancel()
        server.close()
    }

    @Test
    fun `signs in with the stored token and loads players and queues`() = runBlocking<Unit> {
        session.start()

        session.state.awaitValue { it is MaSessionState.Connected }

        assertEquals(FakeMaServer.Command("auth", mapOf("token" to "long.lived.jwt")), server.next())
        assertEquals(setOf("media_player.kitchen", "media_player.office", "syncgroup_1", "media_player.bath"), session.players.value.keys)
        assertTrue(session.queues.value.getValue("media_player.kitchen").isPlaying)
        assertEquals(MaSessionState.Connected("Music Assistant"), session.state.value)
    }

    @Test
    fun `estimates the server clock`() = runBlocking<Unit> {
        var monotonicReads = 0
        var wallReads = 0
        val timed = MaSession(
            scope = scope,
            accounts = accounts,
            connections = { MaConnection(client) },
            retryDelayMs = { 20L },
            wallClock = { wallClock + 0.1 * wallReads++ },
            monotonicClock = { 0.5 * monotonicReads++ },
        )

        timed.start()
        timed.state.awaitValue { it is MaSessionState.Connected }

        assertEquals(wallClock + 0.1 + 2.5 - 0.25, timed.serverNow(), 0.001)
        timed.stop()
    }

    @Test
    fun `carries on with the phone clock when the time command fails`() = runBlocking<Unit> {
        server.reply("time", "error_invalid_command")
        session.start()

        session.state.awaitValue { it is MaSessionState.Connected }

        assertEquals(wallClock, session.serverNow(), 0.001)
    }

    @Test
    fun `carries on with the phone clock when the time reply is not a number`() = runBlocking<Unit> {
        server.reply("time", "command_ok")
        session.start()

        session.state.awaitValue { it is MaSessionState.Connected }

        assertEquals(wallClock, session.serverNow(), 0.001)
    }

    @Test
    fun `applies queue and player events`() = runBlocking<Unit> {
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }

        server.push("event_queue_updated_paused")
        session.queues.awaitValue { it["media_player.kitchen"]?.state == "paused" }
        server.push("event_queue_time_updated")
        val queue = session.queues.awaitValue { it["media_player.kitchen"]?.elapsedTime == 900.5 }.getValue("media_player.kitchen")
        server.push("event_player_updated_unavailable")
        session.players.awaitValue { it["media_player.kitchen"]?.available == false }

        assertEquals(session.serverNow(), queue.elapsedTimeLastUpdated, 0.001)
    }

    @Test
    fun `reconnects after the connection drops`() = runBlocking<Unit> {
        val seen = scope.record(session.state)
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }

        server.dropConnections()

        eventually { seen.count { it is MaSessionState.Connected } == 2 }
        assertTrue(seen.any { it is MaSessionState.Reconnecting })
        assertEquals(2, server.connections)
    }

    @Test
    fun `keeps retrying while the server is down`() = runBlocking<Unit> {
        val seen = scope.record(session.state)
        server.acceptConnections = false
        session.start()

        eventually { MaSessionState.Reconnecting(3) in seen }
        server.acceptConnections = true

        session.state.awaitValue { it is MaSessionState.Connected }
    }

    @Test
    fun `retries after an unexpected failure`() = runBlocking<Unit> {
        var failures = 1
        val flaky = MaSession(
            scope = scope,
            accounts = accounts,
            connections = { if (failures-- > 0) error("boom") else MaConnection(client) },
            retryDelayMs = { 20L },
        )
        val seen = scope.record(flaky.state)

        flaky.start()

        flaky.state.awaitValue { it is MaSessionState.Connected }
        assertTrue(MaSessionState.Reconnecting(1) in seen)
        flaky.stop()
    }

    @Test
    fun `replaces stale players and queues after reconnecting`() = runBlocking<Unit> {
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        server.push("event_queue_updated_paused")
        session.queues.awaitValue { it["media_player.kitchen"]?.state == "paused" }
        server.push("event_player_updated_unavailable")
        session.players.awaitValue { it["media_player.kitchen"]?.available == false }

        server.dropConnections()

        session.players.awaitValue { it["media_player.kitchen"]?.available == true }
        session.queues.awaitValue { it["media_player.kitchen"]?.isPlaying == true }
    }

    @Test
    fun `forgets the speakers when the account goes away`() = runBlocking<Unit> {
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }

        accounts.save(null)
        session.restart()

        session.state.awaitValue { it == MaSessionState.NotConfigured }
        assertTrue(session.players.value.isEmpty())
        assertTrue(session.queues.value.isEmpty())
    }

    @Test
    fun `forgets the speakers when the account points at another server`() = runBlocking<Unit> {
        val other = FakeMaServer()
        other.acceptConnections = false
        try {
            session.start()
            session.state.awaitValue { it is MaSessionState.Connected }

            accounts.save(account(other.url))
            session.restart()

            session.state.awaitValue { it is MaSessionState.Reconnecting }
            assertTrue(session.players.value.isEmpty())
            assertTrue(session.queues.value.isEmpty())
        } finally {
            other.close()
        }
    }

    @Test
    fun `stops and asks for a new sign-in when the token is rejected`() = runBlocking<Unit> {
        server.reply("auth", "auth_invalid_token")
        session.start()

        session.state.awaitValue { it == MaSessionState.SignInRequired }
        Thread.sleep(200)

        assertEquals(1, server.connections)
        assertEquals(MaSessionState.SignInRequired, session.state.value)
    }

    @Test
    fun `closes the socket when the token is rejected`() = runBlocking<Unit> {
        server.reply("auth", "auth_invalid_token")
        session.start()

        session.state.awaitValue { it == MaSessionState.SignInRequired }

        eventually { server.openSockets == 0 }
    }

    @Test
    fun `stops reconnecting when the token is rejected on a later connection`() = runBlocking<Unit> {
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        server.reply("auth", "auth_invalid_token")

        server.dropConnections()

        session.state.awaitValue { it == MaSessionState.SignInRequired }
        Thread.sleep(200)
        assertEquals(2, server.connections)
        eventually { server.openSockets == 0 }
    }

    @Test
    fun `keeps asking for a sign-in after it was stopped`() = runBlocking<Unit> {
        server.reply("auth", "auth_invalid_token")
        session.start()
        session.state.awaitValue { it == MaSessionState.SignInRequired }

        session.stop()

        assertEquals(MaSessionState.SignInRequired, session.state.value)
    }

    @Test
    fun `does not try a rejected token again when started again`() = runBlocking<Unit> {
        server.reply("auth", "auth_invalid_token")
        session.start()
        session.state.awaitValue { it == MaSessionState.SignInRequired }
        val states = scope.record(session.state)

        session.stop()
        session.start()
        Thread.sleep(200)

        assertEquals(1, server.connections)
        assertEquals(listOf<MaSessionState>(MaSessionState.SignInRequired), states.toList())
    }

    @Test
    fun `tries again once the user signs in with a new token`() = runBlocking<Unit> {
        server.reply("auth", "auth_invalid_token")
        session.start()
        session.state.awaitValue { it == MaSessionState.SignInRequired }
        server.reply("auth", "auth_ok")

        accounts.save(accounts.account.value!!.copy(token = "new.long.lived.jwt"))
        session.restart()

        session.state.awaitValue { it is MaSessionState.Connected }
        assertEquals(2, server.connections)
    }

    @Test
    fun `closes the socket when stopped during the handshake`() = runBlocking<Unit> {
        server.reply("auth")
        session.start()
        eventually { server.commands.isNotEmpty() }

        session.stop()

        eventually { server.openSockets == 0 }
        assertEquals(MaSessionState.Disconnected, session.state.value)
    }

    @Test
    fun `replaces the attempt when restarted during the handshake`() = runBlocking<Unit> {
        server.reply("auth")
        session.start()
        eventually { server.commands.isNotEmpty() }
        server.reply("auth", "auth_ok")

        session.restart()

        session.state.awaitValue { it is MaSessionState.Connected }
        eventually { server.openSockets == 1 }
        assertEquals(2, server.connections)
    }

    @Test
    fun `waits for an account`() = runBlocking<Unit> {
        accounts.save(null)

        session.start()

        session.state.awaitValue { it == MaSessionState.NotConfigured }
        assertEquals(0, server.connections)
    }

    @Test
    fun `refuses commands while disconnected`() {
        val error = runCatching { runBlocking { session.call("players/all") } }.exceptionOrNull()

        assertEquals(MaNotConnectedException::class.java, error?.javaClass)
    }

    @Test
    fun `retries with growing delays up to a minute`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 32_000L, 60_000L, 60_000L), listOf(1, 2, 3, 6, 7, 20).map(::defaultRetryDelayMs))
    }
}
