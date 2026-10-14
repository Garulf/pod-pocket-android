package au.com.shiftyjelly.pocketcasts.musicassistant.client

import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.awaitValue
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
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }

        assertEquals(wallClock + 2.5, session.serverNow(), 0.001)
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
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }

        server.dropConnections()

        session.state.awaitValue { it is MaSessionState.Reconnecting }
        session.state.awaitValue { it is MaSessionState.Connected }
        assertEquals(2, server.connections)
    }

    @Test
    fun `keeps retrying while the server is down`() = runBlocking<Unit> {
        server.acceptConnections = false
        session.start()

        session.state.awaitValue { it == MaSessionState.Reconnecting(3) }
        server.acceptConnections = true

        session.state.awaitValue { it is MaSessionState.Connected }
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
