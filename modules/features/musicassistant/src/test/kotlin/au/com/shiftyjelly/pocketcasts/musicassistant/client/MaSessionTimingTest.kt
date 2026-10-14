package au.com.shiftyjelly.pocketcasts.musicassistant.client

import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

@OptIn(ExperimentalCoroutinesApi::class)
class MaSessionTimingTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer().signedIn()
    private val client = MaConnection.pingingClient()
    private val accounts = InMemoryAccountStore(account(server.url))

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `pings the connection every 20 seconds`() {
        assertEquals(TimeUnit.SECONDS.toMillis(20).toInt(), client.pingIntervalMillis)
    }

    @Test
    fun `waits one second, then two, then four before trying again`() = runTest {
        server.acceptConnections = false
        val session = newSession()

        session.start()

        pumpUntil { session.state.value == MaSessionState.Reconnecting(1) }
        assertNoNewRequestAfter(999, expectedRequests = 1)
        advance(1)
        pumpUntil { server.requests == 2 && session.state.value == MaSessionState.Reconnecting(2) }
        assertNoNewRequestAfter(1_999, expectedRequests = 2)
        advance(1)
        pumpUntil { server.requests == 3 && session.state.value == MaSessionState.Reconnecting(3) }
        assertNoNewRequestAfter(3_999, expectedRequests = 3)
        advance(1)
        pumpUntil { server.requests == 4 }
        session.stop()
    }

    @Test
    fun `gives up on a server that stops answering and tries again`() = runTest {
        server.reply("auth")
        val session = newSession(retryDelayMs = { 1_000L })

        session.start()

        pumpUntil { server.commands.isNotEmpty() }
        advance(MaSession.DEFAULT_CALL_TIMEOUT_MS - 1)
        assertEquals(MaSessionState.Connecting, session.state.value)
        advance(1)
        assertEquals(MaSessionState.Reconnecting(1), session.state.value)
        pumpUntil { server.openSockets == 0 }
        server.reply("auth", "auth_ok")
        advance(1_000)
        pumpUntil { session.state.value is MaSessionState.Connected }
        assertEquals(2, server.connections)
        session.stop()
    }

    @Test
    fun `fails a command the server never answers and stays connected`() = runTest {
        server.reply("never/answered")
        val session = newSession()
        session.start()
        pumpUntil { session.state.value is MaSessionState.Connected }
        val waiting = async { runCatching { session.call("never/answered") } }
        pumpUntil { server.commands.any { it.name == "never/answered" } }

        advance(MaSession.DEFAULT_CALL_TIMEOUT_MS)

        pumpUntil { waiting.isCompleted }
        assertEquals(MaTimeoutException::class.java, waiting.await().exceptionOrNull()?.javaClass)
        assertTrue(session.state.value is MaSessionState.Connected)
        session.stop()
    }

    @Test
    fun `lets a command wait longer when asked to`() = runTest {
        server.reply("slow/short")
        server.reply("slow/long")
        val session = newSession()
        session.start()
        pumpUntil { session.state.value is MaSessionState.Connected }
        val short = async { runCatching { session.call("slow/short") } }
        val long = async { runCatching { session.call("slow/long", timeoutMs = 60_000) } }
        pumpUntil { server.commands.count { it.name.startsWith("slow/") } == 2 }

        advance(20_000)
        server.answerLate("slow/long", "token_created")

        pumpUntil { short.isCompleted && long.isCompleted }
        assertEquals(MaTimeoutException::class.java, short.await().exceptionOrNull()?.javaClass)
        assertEquals("long.lived.jwt", long.await().getOrNull())
        session.stop()
    }

    @Test
    fun `adds up to a fifth of the wait as jitter`() = runTest {
        server.acceptConnections = false
        val session = newSession(random = { 0.5 })

        session.start()

        pumpUntil { session.state.value == MaSessionState.Reconnecting(1) }
        assertNoNewRequestAfter(1_099, expectedRequests = 1)
        advance(1)
        pumpUntil { server.requests == 2 }
        session.stop()
    }

    @Test
    fun `keeps backing off when connections drop soon after they open`() = runTest {
        val session = newSession()
        session.start()
        pumpUntil { session.state.value is MaSessionState.Connected }

        server.dropConnections()
        pumpUntil { session.state.value == MaSessionState.Reconnecting(1) }
        advance(1_000)
        pumpUntil { server.connections == 2 && session.state.value is MaSessionState.Connected }
        server.dropConnections()

        pumpUntil { session.state.value == MaSessionState.Reconnecting(2) }
        assertNoNewRequestAfter(1_999, expectedRequests = 2)
        advance(1)
        pumpUntil { server.connections == 3 }
        session.stop()
    }

    @Test
    fun `starts over once a connection has stayed up for 30 seconds`() = runTest {
        val session = newSession()
        session.start()
        pumpUntil { session.state.value is MaSessionState.Connected }
        server.dropConnections()
        pumpUntil { session.state.value == MaSessionState.Reconnecting(1) }
        advance(1_000)
        pumpUntil { server.connections == 2 && session.state.value is MaSessionState.Connected }

        advance(30_000)
        server.dropConnections()

        pumpUntil { session.state.value == MaSessionState.Reconnecting(1) }
        assertNoNewRequestAfter(999, expectedRequests = 2)
        advance(1)
        pumpUntil { server.connections == 3 }
        session.stop()
    }

    @Test
    fun `opens a fresh connection when restarted`() = runTest {
        val session = newSession()
        session.start()
        pumpUntil { session.state.value is MaSessionState.Connected }

        session.restart()

        pumpUntil { server.connections == 2 && session.state.value is MaSessionState.Connected }
        session.stop()
    }

    private fun TestScope.newSession(
        retryDelayMs: (Int) -> Long = ::defaultRetryDelayMs,
        random: () -> Double = { 0.0 },
    ) = MaSession(
        scope = backgroundScope,
        accounts = accounts,
        connections = { MaConnection(client) },
        retryDelayMs = retryDelayMs,
        wallClock = { 1_792_000_000.0 },
        monotonicClock = { testScheduler.currentTime / 1_000.0 },
        random = random,
    )

    private fun TestScope.advance(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    private fun TestScope.assertNoNewRequestAfter(ms: Long, expectedRequests: Int) {
        advance(ms)
        Thread.sleep(100)
        assertEquals(expectedRequests, server.requests)
    }

    private fun TestScope.pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        testScheduler.runCurrent()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition was not met within 10 seconds" }
            Thread.sleep(5)
            testScheduler.runCurrent()
        }
    }
}
