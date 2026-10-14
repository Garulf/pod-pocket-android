package au.com.shiftyjelly.pocketcasts.musicassistant.player

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.UserEpisode
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects
import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryPlaybackStore
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.awaitValue
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.eventually
import au.com.shiftyjelly.pocketcasts.musicassistant.media.EpisodeSource
import au.com.shiftyjelly.pocketcasts.musicassistant.media.MaMediaResolver
import au.com.shiftyjelly.pocketcasts.repositories.playback.Player
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlayerEvent
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class MusicAssistantPlayerTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer().signedIn()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = MaSession(
        scope = scope,
        accounts = InMemoryAccountStore(account(server.url)),
        connections = { MaConnection(OkHttpClient()) },
        wallClock = { NOW },
        monotonicClock = { 0.0 },
    )
    private val sources = mapOf(EPISODE_A to EpisodeSource(EPISODE_A, FEED, guid = null, enclosureUrl = URL_A))
    private val resolver = MaMediaResolver(session, { uuids -> sources.filterKeys { it in uuids } }, { GPODDER })
    private val store = InMemoryPlaybackStore()
    private val events = CopyOnWriteArrayList<PlayerEvent>()
    private var fellBack = false
    private val episodeA = PodcastEpisode(uuid = EPISODE_A, publishedDate = Date(), downloadUrl = URL_A, duration = 1800.0)

    @Before
    fun setUp() = runBlocking<Unit> {
        server.reply("music/podcasts/podcast_episodes", "podcast_episodes_partial", "podcast_episodes_final")
        server.reply("player_queues/all", "queues_all_idle")
        server.reply("player_queues/items", "queue_items")
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        server.commands.clear()
    }

    @After
    fun tearDown() {
        session.stop()
        scope.cancel()
        server.close()
    }

    @Test
    fun `hands the episode off at the phone's position`() = runBlocking<Unit> {
        val player = player()
        player.setEpisode(episodeA)

        player.play(754_900)

        assertEquals("music/podcasts/podcast_episodes", server.next().name)
        assertEquals(FakeMaServer.Command("player_queues/clear", mapOf("queue_id" to KITCHEN)), server.next())
        assertEquals(
            FakeMaServer.Command("player_queues/play_media", mapOf("queue_id" to KITCHEN, "media" to listOf(URI_A), "option" to "add")),
            server.next(),
        )
        assertEquals(FakeMaServer.Command("player_queues/items", mapOf("queue_id" to KITCHEN, "limit" to 1.0, "offset" to 0.0)), server.next())
        assertEquals(
            FakeMaServer.Command("player_queues/play_index", mapOf("queue_id" to KITCHEN, "index" to 0.0, "seek_position" to 754.0)),
            server.next(),
        )
        assertEquals(RemotePlayback(KITCHEN, ITEM_A, EPISODE_A), store.playback)
    }

    @Test
    fun `asks for the first second instead of zero`() = runBlocking<Unit> {
        val player = player()
        player.setEpisode(episodeA)

        player.play(0)

        val playIndex = server.nextExcept("music/podcasts/podcast_episodes", "player_queues/clear", "player_queues/play_media", "player_queues/items")
        assertEquals(1.0, playIndex.args["seek_position"])
    }

    @Test
    fun `follows the speaker once it plays our item`() = runBlocking<Unit> {
        val player = handedOff()

        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }

        assertTrue(player.isPlaying())
        assertEquals(756_700, player.getCurrentPositionMs())
        server.push("event_queue_updated_paused")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPaused }
        assertFalse(player.isPlaying())
        assertEquals(800_000, player.getCurrentPositionMs())
    }

    @Test
    fun `reports the end of the episode`() = runBlocking<Unit> {
        handedOff()
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }

        server.push("event_queue_updated_ended")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == EPISODE_A }
        assertNull(store.playback)
    }

    @Test
    fun `lets go when another app takes over the speaker`() = runBlocking<Unit> {
        val player = handedOff()
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }

        server.push("event_queue_updated_next_item")

        eventually { events.lastOrNull() == PlayerEvent.PlayerPaused }
        assertEquals(-1, player.getCurrentPositionMs())
        player.pause()
        player.stop()
        assertNull(server.commands.firstOrNull { it.name == "player_queues/pause" || it.name == "player_queues/stop" })
    }

    @Test
    fun `ignores the queue it replaced while the handoff settles`() = runBlocking<Unit> {
        handedOff()

        server.push("event_queue_updated_next_item")
        server.push("event_queue_updated_playing")

        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
        assertEquals(listOf(PlayerEvent.PlayerPlaying), events.toList())
    }

    @Test
    fun `resumes, pauses, seeks and stops the speaker it owns`() = runBlocking<Unit> {
        val player = handedOff()
        server.push("event_queue_updated_paused")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPaused }
        server.commands.clear()

        player.play(800_000)
        player.seekToTimeMs(1_000_000)
        player.seekToTimeMs(800_500)
        player.pause()
        player.stop()

        assertEquals(FakeMaServer.Command("player_queues/play", mapOf("queue_id" to KITCHEN)), server.next())
        assertEquals(FakeMaServer.Command("player_queues/seek", mapOf("queue_id" to KITCHEN, "position" to 1000.0)), server.next())
        assertEquals(FakeMaServer.Command("player_queues/pause", mapOf("queue_id" to KITCHEN)), server.next())
        assertEquals(FakeMaServer.Command("player_queues/stop", mapOf("queue_id" to KITCHEN)), server.next())
        assertNull(store.playback)
    }

    @Test
    fun `restarts at the position when the item cannot seek`() = runBlocking<Unit> {
        val player = handedOff()
        server.push("event_queue_updated_paused")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPaused }
        server.reply("player_queues/seek", "error_invalid_command")
        server.commands.clear()

        player.seekToTimeMs(1_000_000)

        assertEquals("player_queues/seek", server.next().name)
        assertEquals(
            FakeMaServer.Command("player_queues/play_index", mapOf("queue_id" to KITCHEN, "index" to 0.0, "seek_position" to 1000.0)),
            server.next(),
        )
    }

    @Test
    fun `sends the playback speed`() = runBlocking<Unit> {
        val player = handedOff()
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
        server.commands.clear()

        player.setPlaybackEffects(PlaybackEffects().apply { playbackSpeed = 1.5 })
        player.setPlaybackEffects(PlaybackEffects().apply { playbackSpeed = 1.0 })

        assertEquals(FakeMaServer.Command("player_queues/set_playback_speed", mapOf("queue_id" to KITCHEN, "speed" to 1.5)), server.next())
        assertTrue(server.commands.isEmpty())
    }

    @Test
    fun `retries as a plain URL when Music Assistant cannot play the episode`() = runBlocking<Unit> {
        server.replyOnce("player_queues/play_media", "error_media_not_found")
        val player = player()
        player.setEpisode(episodeA)

        player.play(60_000)

        val playMedia = generateSequence { server.commands.poll() }.filter { it.name == "player_queues/play_media" }.toList()
        assertEquals(listOf(listOf(URI_A), listOf("builtin://track/$URL_A")), playMedia.map { it.args["media"] })
        assertEquals(RemotePlayback(KITCHEN, ITEM_A, EPISODE_A), store.playback)
        assertFalse(fellBack)
    }

    @Test
    fun `stays on the phone when neither address plays`() = runBlocking<Unit> {
        server.reply("player_queues/play_media", "error_media_not_found")
        val player = player()
        player.setEpisode(episodeA)

        player.play(60_000)

        assertEquals("Could not play on Kitchen", (events.single() as PlayerEvent.PlayerError).message)
        assertTrue(fellBack)
        assertNull(store.playback)
    }

    @Test
    fun `plays only podcast episodes`() = runBlocking<Unit> {
        val player = player()
        player.setEpisode(UserEpisode(uuid = "file", publishedDate = Date()))

        player.play(0)

        assertEquals("Music Assistant can only play podcast episodes", (events.single() as PlayerEvent.PlayerError).message)
    }

    @Test
    fun `attaches to a session this phone started`() = runBlocking<Unit> {
        val player = player(attachTo = RemotePlayback(KITCHEN, ITEM_A, EPISODE_A))
        server.push("event_queue_updated_playing")

        eventually { (events.lastOrNull() as? PlayerEvent.RemoteMetadataNotMatched)?.remoteEpisodeUuid == EPISODE_A }
        player.setEpisode(episodeA)
        player.play(10_000)

        assertTrue(player.isPlaying())
        assertEquals(PlayerEvent.PlayerPlaying, events.last())
        assertTrue(server.commands.isEmpty())
    }

    @Test
    fun `knows nothing before a handoff`() = runBlocking<Unit> {
        val player = player()
        player.setEpisode(episodeA)

        assertEquals(-1, player.getCurrentPositionMs())
        assertFalse(player.isPlaying())
        player.pause()
        player.seekToTimeMs(5_000)
        assertTrue(server.commands.isEmpty())
    }

    private fun player(attachTo: RemotePlayback? = null) = MusicAssistantPlayer(
        remote = session,
        resolver = resolver,
        playbackStore = store,
        queueId = KITCHEN,
        playerName = "Kitchen",
        scope = scope,
        onPlayerEvent = { _: Player, event: PlayerEvent -> events += event },
        attachTo = attachTo,
        onPlaybackFailed = { fellBack = true },
    )

    private suspend fun handedOff(): MusicAssistantPlayer {
        val player = player()
        player.setEpisode(episodeA)
        player.play(754_900)
        events.clear()
        return player
    }

    private companion object {
        const val NOW = 1_792_000_000.0
        const val KITCHEN = "media_player.kitchen"
        const val GPODDER = "gpodder--Xk3dTq9a"
        const val FEED = "https://pod.example.com/feeds/show.xml"
        const val EPISODE_A = "episode-a"
        const val URL_A = "https://cdn.example.com/a.mp3"
        const val ITEM_A = "5b2f0e8c9d6a4f1b8e3c7a2d1f0e9b8a"
        const val URI_A = "$GPODDER://podcast_episode/$FEED https://pod.example.com/feeds/ep-a"
    }
}
