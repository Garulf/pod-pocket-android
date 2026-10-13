package au.com.shiftyjelly.pocketcasts.selfhosted

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpServerConnectionCheckerTest {
    private val server = MockWebServer()
    private val checker = OkHttpServerConnectionChecker(
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
    )

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reports a healthy server as reachable`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status": "ok"}"""))

        val result = checker.check(server.url("/"))

        assertEquals(ConnectionResult.Reachable, result)
        assertEquals("/health", server.takeRequest().path)
    }

    @Test
    fun `checks health under the server base path`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))

        checker.check(server.url("/podpocket"))

        assertEquals("/podpocket/health", server.takeRequest().path)
    }

    @Test
    fun `reports an HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(ConnectionResult.Unreachable("HTTP 404"), checker.check(server.url("/")))
    }

    @Test
    fun `does not resolve a telemetry host`() = runTest {
        val result = checker.check("http://pixel.wp.com".toHttpUrl())

        assertEquals(ConnectionResult.Unreachable(blockedHostMessage("pixel.wp.com")), result)
    }

    @Test
    fun `reports a server that is not Pod Pocket`() = runTest {
        server.enqueue(MockResponse().setBody("<html>hello</html>"))

        assertEquals(ConnectionResult.Unreachable("not a Pod Pocket server"), checker.check(server.url("/")))
    }

    @Test
    fun `reports a server that is down`() = runTest {
        val url = server.url("/")
        server.shutdown()

        assertTrue(checker.check(url) is ConnectionResult.Unreachable)
    }

    @Test
    fun `reports a page that only quotes the health status`() = runTest {
        server.enqueue(MockResponse().setBody("""<html><p>"status": "ok"</p></html>"""))

        assertEquals(ConnectionResult.Unreachable("not a Pod Pocket server"), checker.check(server.url("/")))
    }

    @Test
    fun `reports a health status that is not ok`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"starting"}"""))

        assertEquals(ConnectionResult.Unreachable("not a Pod Pocket server"), checker.check(server.url("/")))
    }

    @Test
    fun `does not accept a huge body`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok","padding":"""" + "x".repeat(1_000_000) + """"}"""))

        assertEquals(ConnectionResult.Unreachable("not a Pod Pocket server"), checker.check(server.url("/")))
    }

    @Test
    fun `does not follow a redirect to a blocked host`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://api.pocketcasts.com/health"))

        val result = checker.check(server.url("/"))

        assertEquals(ConnectionResult.Unreachable("not a Pod Pocket server"), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `does not report another host as reachable`() = runTest {
        val other = MockWebServer()
        try {
            other.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/health").toString()))

            val result = checker.check(server.url("/"))

            assertEquals(ConnectionResult.Unreachable("not a Pod Pocket server"), result)
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `cancels the call when the coroutine is cancelled`() = runTest {
        val canceled = CountDownLatch(1)
        val listeningChecker = OkHttpServerConnectionChecker(
            OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .eventListener(
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            canceled.countDown()
                        }
                    },
                )
                .build(),
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val job = launch(Dispatchers.Default) { listeningChecker.check(server.url("/")) }
        assertEquals("/health", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        job.cancelAndJoin()

        assertTrue(canceled.await(5, TimeUnit.SECONDS))
    }
}
