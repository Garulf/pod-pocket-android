package au.com.shiftyjelly.pocketcasts.musicassistant.media

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEpisodeSourceLookupTest {
    private val server = MockWebServer()
    private val lookup = ServerEpisodeSourceLookup(OkHttpClient(), server.url("/api/"))

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `asks the Pod Pocket server for feed and guid`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"episodes": [{"uuid": "e1", "feed_url": "https://pod.example.com/feeds/show.xml", "guid": "ep-a", "enclosure_url": "https://cdn.example.com/a.mp3"}]}""",
            ),
        )

        val sources = lookup.find(listOf("e1", "e2", "e1"))

        assertEquals(
            mapOf("e1" to EpisodeSource("e1", "https://pod.example.com/feeds/show.xml", "ep-a", "https://cdn.example.com/a.mp3")),
            sources,
        )
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/podpocket/episode_sources", request.path)
        assertEquals("""{"uuids":["e1","e2"]}""", request.body.readUtf8())
    }

    @Test
    fun `finds nothing when the server refuses or answers nonsense`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("<html>"))

        assertTrue(lookup.find(listOf("e1")).isEmpty())
        assertTrue(lookup.find(listOf("e1")).isEmpty())
    }

    @Test
    fun `finds nothing when the server is down`() = runBlocking<Unit> {
        server.shutdown()

        assertTrue(lookup.find(listOf("e1")).isEmpty())
    }
}
