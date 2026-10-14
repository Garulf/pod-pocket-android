package au.com.shiftyjelly.pocketcasts.musicassistant.media

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.awaitValue
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class MaMediaResolverTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer().signedIn()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = MaSession(scope, InMemoryAccountStore(account(server.url)), { MaConnection(OkHttpClient()) })
    private val sources = mutableMapOf<String, EpisodeSource>()
    private var gpodder: String? = GPODDER
    private val resolver = MaMediaResolver(session, { uuids -> sources.filterKeys { it in uuids } }, { gpodder })

    @Before
    fun setUp() = runBlocking<Unit> {
        server.reply("music/podcasts/podcast_episodes", "podcast_episodes_partial", "podcast_episodes_final")
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
    }

    @After
    fun tearDown() {
        session.stop()
        scope.cancel()
        server.close()
    }

    @Test
    fun `finds the episode by its enclosure URL`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = "unrelated", enclosureUrl = "https://cdn.example.com/a.mp3")

        val media = resolver.resolve(episode("a", "https://cdn.example.com/a.mp3"))

        assertEquals(MaMedia.ProviderEpisode("a", "$GPODDER://podcast_episode/$FEED https://pod.example.com/feeds/ep-a"), media)
        assertEquals(
            mapOf("item_id" to FEED, "provider_instance_id_or_domain" to GPODDER),
            server.nextExcept("auth", "time", "players/all", "player_queues/all").args,
        )
    }

    @Test
    fun `falls back to the guid when the enclosure changed`() = runBlocking<Unit> {
        sources["b"] = EpisodeSource("b", FEED, guid = "urn:uuid:7a4f3c1e-0b2d-4e8a-9c5f-2d1e3b4a5c6d", enclosureUrl = "https://cdn.example.com/b-new.mp3")

        val media = resolver.resolve(episode("b", "https://cdn.example.com/b-new.mp3"))

        assertEquals(MaMedia.ProviderEpisode("b", "$GPODDER://podcast_episode/$FEED urn:uuid:7a4f3c1e-0b2d-4e8a-9c5f-2d1e3b4a5c6d"), media)
    }

    @Test
    fun `resolves a relative permalink guid the way the gPodder provider does`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = "ep-a", enclosureUrl = "https://cdn.example.com/moved.mp3")

        val media = resolver.resolve(episode("a", "https://cdn.example.com/moved.mp3"))

        assertEquals(MaMedia.ProviderEpisode("a", "$GPODDER://podcast_episode/$FEED https://pod.example.com/feeds/ep-a"), media)
    }

    @Test
    fun `plays the plain URL when Music Assistant does not know the episode`() = runBlocking<Unit> {
        sources["x"] = EpisodeSource("x", FEED, guid = "nope", enclosureUrl = "https://cdn.example.com/x.mp3")

        assertEquals(MaMedia.PlainUrl("x", "builtin://track/https://cdn.example.com/x.mp3"), resolver.resolve(episode("x", "https://cdn.example.com/x.mp3")))
    }

    @Test
    fun `plays the plain URL when the podcast is not in Music Assistant`() = runBlocking<Unit> {
        server.reply("music/podcasts/podcast_episodes", "error_media_not_found")
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3")

        assertEquals(MaMedia.PlainUrl("a", "builtin://track/https://cdn.example.com/a.mp3"), resolver.resolve(episode("a", "https://cdn.example.com/a.mp3")))
    }

    @Test
    fun `plays the plain URL without a gPodder provider or a server answer`() = runBlocking<Unit> {
        assertEquals(MaMedia.PlainUrl("a", "builtin://track/https://cdn.example.com/a.mp3"), resolver.resolve(episode("a", "https://cdn.example.com/a.mp3")))
        gpodder = null
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3")
        assertEquals(MaMedia.PlainUrl("a", "builtin://track/https://cdn.example.com/a.mp3"), resolver.resolve(episode("a", "https://cdn.example.com/a.mp3")))
    }

    @Test
    fun `has nothing to play for an episode without a web URL`() = runBlocking<Unit> {
        assertNull(resolver.resolve(episode("a", null)))
        assertNull(resolver.resolve(episode("a", "/storage/emulated/0/a.mp3")))
    }

    @Test
    fun `lists a feed once for several episodes`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3")
        sources["c"] = EpisodeSource("c", FEED, guid = "has space", enclosureUrl = "https://cdn.example.com/c.mp3")

        resolver.resolve(episode("a", "https://cdn.example.com/a.mp3"))
        val media = resolver.resolve(episode("c", "https://cdn.example.com/c.mp3"))

        assertEquals(MaMedia.ProviderEpisode("c", "$GPODDER://podcast_episode/$FEED https://cdn.example.com/c.mp3"), media)
        val listings = generateSequence { server.commands.poll() }.count { it.name == "music/podcasts/podcast_episodes" }
        assertEquals(1, listings)
    }

    private fun episode(uuid: String, url: String?) = PodcastEpisode(uuid = uuid, publishedDate = Date(), downloadUrl = url)

    private companion object {
        const val GPODDER = "gpodder--Xk3dTq9a"
        const val FEED = "https://pod.example.com/feeds/show.xml"
    }
}
