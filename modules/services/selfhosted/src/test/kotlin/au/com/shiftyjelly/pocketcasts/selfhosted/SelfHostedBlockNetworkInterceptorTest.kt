package au.com.shiftyjelly.pocketcasts.selfhosted

import java.net.InetAddress
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SelfHostedBlockNetworkInterceptorTest {
    private val origin = MockWebServer()
    private val destination = MockWebServer()
    private val client = OkHttpClient.Builder()
        .dns { listOf(InetAddress.getLoopbackAddress()) }
        .addNetworkInterceptor(SelfHostedBlockNetworkInterceptor())
        .build()

    @After
    fun tearDown() {
        origin.shutdown()
        destination.shutdown()
    }

    @Test
    fun `refuses a redirect to a telemetry host`() {
        origin.enqueue(redirectTo("http://public-api.wordpress.com:${origin.port}/rest/v1.1/tracks/record"))

        assertThrows(BlockedHostException::class.java) { get(origin) }
        assertEquals(1, origin.requestCount)
    }

    @Test
    fun `refuses a redirect to a Pocket Casts host`() {
        origin.enqueue(redirectTo("http://static.pocketcasts.com:${origin.port}/discover/android/content_v3.json"))

        assertThrows(BlockedHostException::class.java) { get(origin) }
        assertEquals(1, origin.requestCount)
    }

    @Test
    fun `follows a redirect to an ordinary host`() {
        destination.enqueue(MockResponse().setBody("episode"))
        origin.enqueue(redirectTo(destination.url("/episode.mp3").toString()))

        val body = client.newCall(Request.Builder().url(origin.url("/feed")).build()).execute().use { it.body.string() }

        assertEquals("episode", body)
        assertEquals(1, destination.requestCount)
    }

    private fun redirectTo(location: String) = MockResponse().setResponseCode(302).setHeader("Location", location)

    private fun get(server: MockWebServer) {
        client.newCall(Request.Builder().url(server.url("/feed")).build()).execute().close()
    }
}
