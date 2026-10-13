package au.com.shiftyjelly.pocketcasts.selfhosted

import java.io.IOException
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfHostedRewriteInterceptorTest {
    private val terminal = CapturingTerminal()

    @Test
    fun `rewrites every Pocket Casts host to its server prefix`() {
        val cases = mapOf(
            "https://api.pocketcasts.com/user/login_pocket_casts" to "https://pod.example.com/api/user/login_pocket_casts",
            "https://api.pocketcasts.net/subscription/status" to "https://pod.example.com/api/subscription/status",
            "https://refresh.pocketcasts.com/user/update" to "https://pod.example.com/refresh/user/update",
            "https://refresh.pocketcasts.net/podcasts/search" to "https://pod.example.com/refresh/podcasts/search",
            "https://cache.pocketcasts.com/mobile/podcast/full/abc" to "https://pod.example.com/cache/mobile/podcast/full/abc",
            "https://podcast-api.pocketcasts.net/mobile/podcast/full/abc" to "https://pod.example.com/cache/mobile/podcast/full/abc",
            "https://search.pocketcasts.com/autocomplete/search" to "https://pod.example.com/search/autocomplete/search",
            "https://search.pocketcasts.net/autocomplete/search" to "https://pod.example.com/search/autocomplete/search",
            "https://static.pocketcasts.com/discover/images/webp/480/abc.webp" to "https://pod.example.com/static/discover/images/webp/480/abc.webp",
            "https://static.pocketcasts.net/discover/android/content_v3.json" to "https://pod.example.com/static/discover/android/content_v3.json",
            "https://lists.pocketcasts.com/abc.json" to "https://pod.example.com/lists/abc.json",
            "https://sharing.pocketcasts.com/share/list" to "https://pod.example.com/sharing/share/list",
            "https://shownotes.pocketcasts.com/generated_transcripts/p/e.vtt" to "https://pod.example.com/shownotes/generated_transcripts/p/e.vtt",
            "https://web-feeds-api.pocketcasts.com/feeds" to "https://pod.example.com/web-feeds/feeds",
            "https://pca.st/episode/abc" to "https://pod.example.com/short/episode/abc",
            "https://pcast.pocketcasts.net/episode/abc" to "https://pod.example.com/short/episode/abc",
        )

        cases.forEach { (original, expected) ->
            assertEquals(original, expected, fetch(original, server = "https://pod.example.com/".toHttpUrl()).toString())
        }
    }

    @Test
    fun `keeps the query string byte for byte`() {
        val url = fetch(
            "https://search.pocketcasts.com/autocomplete/search?q=a%20b+c&q=%26&q=caf%C3%A9&empty=&flag",
            server = "https://pod.example.com/".toHttpUrl(),
        )

        assertEquals("q=a%20b+c&q=%26&q=caf%C3%A9&empty=&flag", url.encodedQuery)
        assertEquals("/search/autocomplete/search", url.encodedPath)
    }

    @Test
    fun `keeps the server base path, port and scheme`() {
        val url = fetch(
            "https://cache.pocketcasts.com/mobile/show_notes/full/abc?disableredirect=true",
            server = "http://192.168.1.5:8000/podpocket".toHttpUrl(),
        )

        assertEquals("http://192.168.1.5:8000/podpocket/cache/mobile/show_notes/full/abc?disableredirect=true", url.toString())
    }

    @Test
    fun `keeps a trailing slash on the original path`() {
        val url = fetch("https://static.pocketcasts.com/", server = "https://pod.example.com/".toHttpUrl())

        assertEquals("https://pod.example.com/static/", url.toString())
    }

    @Test
    fun `keeps method, headers and body`() {
        val body = FormBody.Builder().add("podcasts", "a,b").add("last_episodes", "null,null").build()
        val request = Request.Builder()
            .url("https://refresh.pocketcasts.com/user/update")
            .header("Authorization", "Bearer token")
            .post(body)
            .build()

        client(server = "https://pod.example.com/".toHttpUrl()).newCall(request).execute().close()

        val sent = terminal.requests.single()
        assertEquals("POST", sent.method)
        assertEquals("Bearer token", sent.header("Authorization"))
        assertEquals("podcasts=a%2Cb&last_episodes=null%2Cnull", Buffer().also { sent.body!!.writeTo(it) }.readUtf8())
    }

    @Test
    fun `fails Pocket Casts requests as network errors when no server is set`() {
        val error = assertThrows(IOException::class.java) {
            fetch("https://cache.pocketcasts.com/mobile/podcast/full/abc", server = null)
        }

        assertTrue(error is ServerNotConfiguredException)
        assertTrue(terminal.requests.isEmpty())
    }

    @Test
    fun `blocks telemetry and unknown Pocket Casts hosts`() {
        val blocked = listOf(
            "https://public-api.wordpress.com/rest/v1.1/tracks/record",
            "https://public-api.wordpress.com/wpcom/v2/experiments/0.1.0/assignments/pocketcasts",
            "https://pixel.wp.com/t.gif",
            "https://o12345.ingest.sentry.io/api/1/envelope/",
            "https://www.gravatar.com/avatar/abc",
            "https://automattic.com/privacy/",
            "https://pocketcasts.com/",
            "https://play.pocketcasts.com/web",
            "https://support.pocketcasts.com/article/1",
        )

        blocked.forEach { url ->
            assertThrows(url, BlockedHostException::class.java) {
                fetch(url, server = "https://pod.example.com/".toHttpUrl())
            }
        }
        assertTrue(terminal.requests.isEmpty())
    }

    @Test
    fun `passes other hosts through unchanged`() {
        val untouched = listOf(
            "https://feeds.example.com/rss.xml?x=1",
            "https://pod.example.com/static/discover/json/top.json",
            "https://notpocketcasts.com/feed",
            "https://pocketcasts.com.example.org/feed",
            "https://cdn.wordpress.example.org/episode.mp3",
        )

        untouched.forEach { url ->
            assertEquals(url, fetch(url, server = "https://pod.example.com/".toHttpUrl()).toString())
        }
    }

    @Test
    fun `passes other hosts through when no server is set`() {
        val url = fetch("https://feeds.example.com/rss.xml", server = null)

        assertEquals("https://feeds.example.com/rss.xml", url.toString())
    }

    private fun client(server: HttpUrl?) = OkHttpClient.Builder()
        .addInterceptor(SelfHostedRewriteInterceptor { server })
        .addInterceptor(terminal)
        .build()

    private fun fetch(url: String, server: HttpUrl?): HttpUrl {
        client(server).newCall(Request.Builder().url(url).build()).execute().close()
        return terminal.requests.last().url
    }

    private class CapturingTerminal : Interceptor {
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody())
                .build()
        }
    }
}
