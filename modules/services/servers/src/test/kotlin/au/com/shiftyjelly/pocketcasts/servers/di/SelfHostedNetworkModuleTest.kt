package au.com.shiftyjelly.pocketcasts.servers.di

import au.com.shiftyjelly.pocketcasts.selfhosted.BlockedHostException
import au.com.shiftyjelly.pocketcasts.selfhosted.SelfHostedBlockNetworkInterceptor
import au.com.shiftyjelly.pocketcasts.selfhosted.SelfHostedBlockingDns
import au.com.shiftyjelly.pocketcasts.selfhosted.SelfHostedRewriteInterceptor
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SelfHostedNetworkModuleTest {
    @get:Rule
    val server = MockWebServer()

    @get:Rule
    val folder = TemporaryFolder()

    private val module = NetworkModule()

    @Test
    fun `every client built from the raw client talks to the self-hosted server`() {
        derivedClients().forEach { (name, client) ->
            server.enqueue(MockResponse().setBody("{}"))

            client.newCall(Request.Builder().url("https://static.pocketcasts.com/discover/android/content_v3.json").build())
                .execute()
                .close()

            assertEquals(name, "/static/discover/android/content_v3.json", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        }
    }

    @Test
    fun `every client built from the raw client refuses telemetry hosts`() {
        derivedClients().forEach { (name, client) ->
            assertThrows(name, BlockedHostException::class.java) {
                client.newCall(Request.Builder().url("https://public-api.wordpress.com/rest/v1.1/tracks/record").build()).execute()
            }
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `every client built from the raw client refuses a redirect to a telemetry host`() {
        derivedClients().forEach { (name, client) ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "http://public-api.wordpress.com:${server.port}/rest/v1.1/tracks/record"),
            )

            assertThrows(name, UnknownHostException::class.java) {
                client.newCall(Request.Builder().url(server.url("/feed")).build()).execute()
            }

            assertEquals(name, "/feed", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        }

        assertEquals(derivedClients().size, server.requestCount)
    }

    private fun derivedClients(): Map<String, OkHttpClient> {
        val raw = module.provideRawClient(
            dispatcher = Dispatcher(),
            selfHostedRewriteInterceptor = SelfHostedRewriteInterceptor { server.url("/") },
            selfHostedBlockNetworkInterceptor = SelfHostedBlockNetworkInterceptor(),
            selfHostedBlockingDns = SelfHostedBlockingDns(),
        )
        return mapOf(
            "cached" to module.provideCachedClient(raw, Cache(folder.newFolder(), 1024L * 1024L), emptyList()),
            "noCache" to module.provideNoCacheClient(raw, emptyList()),
            "noCacheTokened" to module.provideNoCacheTokenedClient(raw, emptyList()),
            "downloads" to module.provideDownloadsClient(raw, emptyList()),
            "transcripts" to module.provideTranscriptsClient(raw, Cache(folder.newFolder(), 1024L * 1024L), emptyList()),
            "player" to module.providePlayerClient(raw, emptyList()),
            "artwork" to module.provideArtworkClient(raw, emptyList()),
        )
    }
}
