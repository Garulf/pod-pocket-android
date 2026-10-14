package au.com.shiftyjelly.pocketcasts.musicassistant.media

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.awaitValue
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaRemote
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaTimeoutException
import au.com.shiftyjelly.pocketcasts.musicassistant.eventually
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaPlayer
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaQueue
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private val lookups = AtomicInteger()
    private var nowMs = 0L
    private val resolver = MaMediaResolver(
        remote = session,
        sources = { uuids ->
            lookups.incrementAndGet()
            sources.filterKeys { it in uuids }
        },
        gpodderInstanceId = { gpodder },
        now = { nowMs },
    )

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

    @Test
    fun `lists a feed again once the cached listing is stale`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3")

        resolver.resolve(episode("a", "https://cdn.example.com/a.mp3"))
        nowMs += 10 * 60 * 1000 - 1
        resolver.resolve(episode("a", "https://cdn.example.com/a.mp3"))
        nowMs += 1
        resolver.resolve(episode("a", "https://cdn.example.com/a.mp3"))

        assertEquals(2, listings())
    }

    @Test
    fun `keeps listings apart per gPodder instance`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3")

        resolver.resolve(episode("a", "https://cdn.example.com/a.mp3"))
        gpodder = "gpodder--Other123"
        resolver.resolve(episode("a", "https://cdn.example.com/a.mp3"))

        val instances = generateSequence { server.commands.poll() }
            .filter { it.name == "music/podcasts/podcast_episodes" }
            .map { it.args["provider_instance_id_or_domain"] }
            .toList()
        assertEquals(listOf(GPODDER, "gpodder--Other123"), instances)
    }

    @Test
    fun `lists a feed once when episodes are resolved together`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3")
        sources["c"] = EpisodeSource("c", FEED, guid = null, enclosureUrl = "https://cdn.example.com/c.mp3")
        val gate = CompletableDeferred<Unit>()
        val remote = ScriptedRemote(gate) { listOf(episodeItem("a", "https://cdn.example.com/a.mp3"), episodeItem("c", "https://cdn.example.com/c.mp3")) }
        val scripted = MaMediaResolver(remote, { uuids -> sources.filterKeys { it in uuids }.also { lookups.incrementAndGet() } }, { GPODDER })

        val resolved = listOf(
            async(Dispatchers.Default) { scripted.resolve(episode("a", "https://cdn.example.com/a.mp3")) },
            async(Dispatchers.Default) { scripted.resolve(episode("c", "https://cdn.example.com/c.mp3")) },
        )
        eventually { lookups.get() == 2 && remote.calls.get() >= 1 }
        delay(300)
        gate.complete(Unit)

        assertTrue(resolved.awaitAll().all { it is MaMedia.ProviderEpisode })
        assertEquals(1, remote.calls.get())
    }

    @Test
    fun `lists each feed once per batch even when the listing fails`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3")
        sources["c"] = EpisodeSource("c", FEED, guid = null, enclosureUrl = "https://cdn.example.com/c.mp3")
        val remote = ScriptedRemote { throw MaTimeoutException("music/podcasts/podcast_episodes") }
        val scripted = MaMediaResolver(remote, { uuids -> sources.filterKeys { it in uuids }.also { lookups.incrementAndGet() } }, { GPODDER })

        val media = scripted.resolveAll(listOf(episode("a", "https://cdn.example.com/a.mp3"), episode("c", "https://cdn.example.com/c.mp3")))

        assertEquals(
            listOf(
                MaMedia.PlainUrl("a", "builtin://track/https://cdn.example.com/a.mp3"),
                MaMedia.PlainUrl("c", "builtin://track/https://cdn.example.com/c.mp3"),
            ),
            media,
        )
        assertEquals(1, remote.calls.get())
        assertEquals(1, lookups.get())
    }

    @Test
    fun `keeps the order and leaves out episodes without a stream`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3")

        val media = resolver.resolveAll(
            listOf(
                episode("x", "https://cdn.example.com/x.mp3"),
                episode("b", null),
                episode("a", "https://cdn.example.com/a.mp3"),
            ),
        )

        assertEquals(
            listOf(
                MaMedia.PlainUrl("x", "builtin://track/https://cdn.example.com/x.mp3"),
                MaMedia.ProviderEpisode("a", "$GPODDER://podcast_episode/$FEED https://pod.example.com/feeds/ep-a"),
            ),
            media,
        )
        assertEquals(1, lookups.get())
    }

    @Test
    fun `plays the plain URL at once when Music Assistant is not connected`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3")
        session.stop()

        val media = resolver.resolve(episode("a", "https://cdn.example.com/a.mp3"))

        assertEquals(MaMedia.PlainUrl("a", "builtin://track/https://cdn.example.com/a.mp3"), media)
        assertEquals(0, lookups.get())
    }

    @Test
    fun `plays the plain URL when Music Assistant does not answer the listing`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3")
        val silent = MaMediaResolver(ScriptedRemote { throw MaTimeoutException("music/podcasts/podcast_episodes") }, { sources }, { GPODDER })

        val media = silent.resolve(episode("a", "https://cdn.example.com/a.mp3"))

        assertEquals(MaMedia.PlainUrl("a", "builtin://track/https://cdn.example.com/a.mp3"), media)
    }

    @Test
    fun `matches addresses that differ only in scheme case, host case and an empty path`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "HTTPS://CDN.Example.com/")
        val listing = listOf(mapOf("item_id" to "ep", "provider" to "gpodder", "uri" to "gpodder--Xk3dTq9a://podcast_episode/ep", "provider_mappings" to listOf(mapOf("item_id" to "ep", "provider_instance" to GPODDER, "url" to "https://cdn.example.com"))))
        val scripted = MaMediaResolver(ScriptedRemote { listing }, { sources }, { GPODDER })

        val media = scripted.resolve(episode("a", "https://cdn.example.com"))

        assertEquals(MaMedia.ProviderEpisode("a", "gpodder--Xk3dTq9a://podcast_episode/ep"), media)
    }

    @Test
    fun `does not match an episode without a download URL to a mapping without a URL`() = runBlocking<Unit> {
        sources["a"] = EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3")
        val listing = listOf(mapOf("item_id" to "ep", "provider" to "gpodder", "uri" to "gpodder--Xk3dTq9a://podcast_episode/ep", "provider_mappings" to listOf(mapOf("item_id" to "ep", "provider_instance" to GPODDER))))
        val scripted = MaMediaResolver(ScriptedRemote { listing }, { sources }, { GPODDER })

        assertNull(scripted.resolve(episode("a", null)))
    }

    @Test
    fun `accepts web URLs in any case`() {
        assertEquals(
            MaMedia.PlainUrl("a", "builtin://track/HTTPS://cdn.example.com/a.mp3"),
            resolver.plainUrl(episode("a", "HTTPS://cdn.example.com/a.mp3")),
        )
    }

    private fun listings() = generateSequence { server.commands.poll() }.count { it.name == "music/podcasts/podcast_episodes" }

    private fun episodeItem(id: String, url: String) = mapOf(
        "item_id" to id,
        "provider" to "gpodder",
        "uri" to "$GPODDER://podcast_episode/$id",
        "provider_mappings" to listOf(mapOf("item_id" to id, "provider_instance" to GPODDER, "url" to url)),
    )

    private class ScriptedRemote(private val gate: CompletableDeferred<Unit>? = null, private val listing: () -> Any?) : MaRemote {
        val calls = AtomicInteger()
        override val players: StateFlow<Map<String, MaPlayer>> = MutableStateFlow(emptyMap())
        override val queues: StateFlow<Map<String, MaQueue>> = MutableStateFlow(emptyMap())
        override val isConnected: Boolean = true

        override suspend fun call(command: String, args: Map<String, Any?>, timeoutMs: Long?): Any? {
            calls.incrementAndGet()
            gate?.await()
            return listing()
        }

        override fun serverNow(): Double = 0.0
    }

    private fun episode(uuid: String, url: String?) = PodcastEpisode(uuid = uuid, publishedDate = Date(), downloadUrl = url)

    private companion object {
        const val GPODDER = "gpodder--Xk3dTq9a"
        const val FEED = "https://pod.example.com/feeds/show.xml"
    }
}
