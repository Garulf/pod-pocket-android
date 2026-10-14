package au.com.shiftyjelly.pocketcasts.musicassistant.media

import au.com.shiftyjelly.pocketcasts.musicassistant.eventually
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class ServerEpisodeSourceLookupTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val lookup = ServerEpisodeSourceLookup(client, server.url("/api/"))
    private val logged = CopyOnWriteArrayList<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logged += message
        }
    }

    @Before
    fun plantTree() {
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
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

    @Test
    fun `keeps the rows it can read when one is malformed`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"episodes": [{"uuid": "e1", "feed_url": "https://pod.example.com/feeds/show.xml"}, {"uuid": "e2", "feed_url": "https://pod.example.com/feeds/show.xml", "guid": null, "enclosure_url": "https://cdn.example.com/b.mp3"}]}""",
            ),
        )

        val sources = lookup.find(listOf("e1", "e2"))

        assertEquals(setOf("e2"), sources.keys)
    }

    @Test
    fun `logs the status of a refusal and nothing of the answer`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(500).setHeader("X-Secret", "header-secret").setBody("body-secret"))

        lookup.find(listOf("e1"))

        assertTrue(logged.any { "500" in it })
        assertFalse(logged.any { "body-secret" in it || "header-secret" in it })
    }

    @Test
    fun `cancels the request when the caller gives up`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = launch(Dispatchers.Default) { lookup.find(listOf("e1")) }
        server.takeRequest()
        eventually { client.dispatcher.runningCallsCount() == 1 }

        pending.cancelAndJoin()

        eventually { client.dispatcher.runningCallsCount() == 0 }
    }
}
