package au.com.shiftyjelly.pocketcasts.selfhosted

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
}
