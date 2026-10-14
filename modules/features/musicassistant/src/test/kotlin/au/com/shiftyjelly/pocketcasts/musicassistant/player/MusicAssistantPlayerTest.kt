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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        wallClock = { wallNow },
        monotonicClock = { 0.0 },
    )
    private val sources = mapOf(EPISODE_A to EpisodeSource(EPISODE_A, FEED, guid = null, enclosureUrl = URL_A))
    private val resolver = MaMediaResolver(session, { uuids -> sources.filterKeys { it in uuids } }, { GPODDER })
    private val store = InMemoryPlaybackStore()
    private val events = CopyOnWriteArrayList<PlayerEvent>()
    private val bufferingSignals = AtomicInteger()
    private val unheard = AtomicInteger()
    private var listening = true
    private var nowMs = 0L

    @Volatile
    private var wallNow = NOW

    @Volatile
    private var clockHook: () -> Unit = {}
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
        player.seekToTimeMs(1_000_500)
        player.pause()
        player.stop()

        assertEquals(FakeMaServer.Command("player_queues/play", mapOf("queue_id" to KITCHEN)), server.next())
        assertEquals(FakeMaServer.Command("player_queues/seek", mapOf("queue_id" to KITCHEN, "position" to 1000.0)), server.next())
        assertEquals(FakeMaServer.Command("player_queues/pause", mapOf("queue_id" to KITCHEN)), server.next())
        assertEquals(FakeMaServer.Command("player_queues/stop", mapOf("queue_id" to KITCHEN)), server.next())
        assertNull(store.playback)
    }

    @Test
    fun `keeps a seek the paused item refuses until playback resumes`() = runBlocking<Unit> {
        val player = handedOff()
        server.push("event_queue_updated_paused")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPaused }
        server.reply("player_queues/seek", "error_invalid_command")
        server.commands.clear()

        player.seekToTimeMs(1_000_000)

        assertEquals("player_queues/seek", server.next().name)
        assertTrue(server.commands.isEmpty())
        assertEquals(1_000_000, player.getCurrentPositionMs())

        player.play(1_000_000)

        assertEquals(
            FakeMaServer.Command("player_queues/play_index", mapOf("queue_id" to KITCHEN, "index" to 0.0, "seek_position" to 1000.0)),
            server.next(),
        )
        assertTrue(server.commands.isEmpty())
    }

    @Test
    fun `restarts at the position when a playing item cannot seek`() = runBlocking<Unit> {
        val player = handedOff()
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
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
    fun `follows the queue time`() = runBlocking<Unit> {
        val player = handedOff()
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }

        server.push("event_queue_time_updated")

        eventually { runBlocking { player.getCurrentPositionMs() } == 900_500 }
    }

    @Test
    fun `keeps the position steady and the ownership across a reconnect`() = runBlocking<Unit> {
        server.reply("player_queues/all", "queues_all")
        val player = handedOff()
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
        assertEquals(756_700, player.getCurrentPositionMs())

        server.dropConnections()
        eventually { !session.isConnected }
        assertEquals(756_700, player.getCurrentPositionMs())
        eventually { session.isConnected }

        assertTrue(player.isPlaying())
        assertEquals(756_700, player.getCurrentPositionMs())
        assertEquals(listOf(PlayerEvent.PlayerPlaying), events.toList())
    }

    @Test
    fun `hands the episode off at the listener's speed`() = runBlocking<Unit> {
        val player = player()
        player.setEpisode(episodeA)

        player.setPlaybackEffects(PlaybackEffects().apply { playbackSpeed = 1.5 })
        assertTrue(server.commands.isEmpty())
        player.play(754_900)

        val commands = generateSequence { server.commands.poll() }.filter { it.name.startsWith("player_queues/") }.toList()
        assertEquals(
            listOf("clear", "play_media", "items", "play_index", "set_playback_speed").map { "player_queues/$it" },
            commands.map { it.name },
        )
        assertEquals(mapOf("queue_id" to KITCHEN, "speed" to 1.5), commands.last().args)
    }

    @Test
    fun `keeps the speed within what Music Assistant accepts`() = runBlocking<Unit> {
        val player = handedOff()
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
        server.commands.clear()

        player.setPlaybackEffects(PlaybackEffects().apply { playbackSpeed = 5.0 })

        assertEquals(FakeMaServer.Command("player_queues/set_playback_speed", mapOf("queue_id" to KITCHEN, "speed" to 3.0)), server.next())
    }

    @Test
    fun `reports buffering until the speaker starts`() = runBlocking<Unit> {
        val player = player()
        player.setEpisode(episodeA)

        player.play(754_900)

        assertTrue(player.isBuffering())
        assertTrue(bufferingSignals.get() >= 1)
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
        assertFalse(player.isBuffering())
    }

    @Test
    fun `stops reporting buffering when the handoff fails`() = runBlocking<Unit> {
        server.reply("player_queues/play_media", "error_media_not_found")
        val player = player()
        player.setEpisode(episodeA)

        player.play(60_000)

        assertFalse(player.isBuffering())
    }

    @Test
    fun `does not announce a pause when the player moves on to another episode`() = runBlocking<Unit> {
        val player = handedOff()
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
        events.clear()
        server.commands.clear()

        player.setEpisode(PodcastEpisode(uuid = "episode-b", publishedDate = Date(), downloadUrl = "https://cdn.example.com/b.mp3"))
        player.play(0)

        assertEquals("player_queues/clear", server.nextExcept("music/podcasts/podcast_episodes").name)
        assertFalse(events.any { it == PlayerEvent.PlayerPaused })
        assertEquals(RemotePlayback(KITCHEN, ITEM_A, "episode-b"), store.playback)
    }

    @Test
    fun `keeps overlapping plays from interleaving their commands`() = runBlocking<Unit> {
        val player = player()
        player.setEpisode(episodeA)

        listOf(
            async(Dispatchers.Default) { player.play(754_900) },
            async(Dispatchers.Default) { player.play(754_900) },
        ).awaitAll()

        val names = generateSequence { server.commands.poll() }.map { it.name }.filter { it != "music/podcasts/podcast_episodes" }.toList()
        val handoff = listOf("clear", "play_media", "items", "play_index").map { "player_queues/$it" }
        assertEquals(handoff + handoff, names)
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
    fun `gives up at once when the speaker is unavailable`() = runBlocking<Unit> {
        server.reply("player_queues/play_media", "error_player_unavailable")
        val player = player()
        player.setEpisode(episodeA)

        player.play(60_000)

        val playMedia = generateSequence { server.commands.poll() }.count { it.name == "player_queues/play_media" }
        assertEquals(1, playMedia)
        assertEquals("Could not play on Kitchen", (events.single() as PlayerEvent.PlayerError).message)
        assertTrue(fellBack)
        assertNull(store.playback)
    }

    @Test
    fun `announces an attached session again when the first announcement went unheard`() = runBlocking<Unit> {
        server.push("event_queue_updated_playing")
        eventually { session.queues.value[KITCHEN]?.currentItem != null }
        listening = false
        val player = player(attachTo = RemotePlayback(KITCHEN, ITEM_A, EPISODE_A))
        eventually { unheard.get() == 1 }
        listening = true
        assertTrue(events.isEmpty())

        nowMs += 2_000
        player.getCurrentPositionMs()

        assertEquals(EPISODE_A, (events.single() as PlayerEvent.RemoteMetadataNotMatched).remoteEpisodeUuid)
        nowMs += 1_000
        player.getCurrentPositionMs()
        assertEquals(1, events.size)
        player.setEpisode(episodeA)
        nowMs += 5_000
        player.getCurrentPositionMs()
        assertEquals(1, events.size)
    }

    @Test
    fun `stops announcing an attached session after ten attempts`() = runBlocking<Unit> {
        val player = attachedWithUnheardAnnouncement()

        repeat(30) {
            nowMs += 2_000
            player.getCurrentPositionMs()
        }

        assertEquals(9, events.size)
        assertEquals(1, unheard.get())
    }

    @Test
    fun `announces an attached session once when two callers ask at the same moment`() = runBlocking<Unit> {
        val player = attachedWithUnheardAnnouncement()
        nowMs += 2_000
        val bothAsking = CyclicBarrier(2)
        clockHook = { runCatching { bothAsking.await(300, TimeUnit.MILLISECONDS) } }

        List(2) { async(Dispatchers.Default) { player.getCurrentPositionMs() } }.awaitAll()

        assertEquals(1, events.size)
    }

    @Test
    fun `shows the handoff position until the speaker reports one`() = runBlocking<Unit> {
        val player = player()
        player.setEpisode(episodeA)
        player.play(754_900)

        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }

        assertEquals(754_900, player.getCurrentPositionMs())
    }

    @Test
    fun `shows the seek target while the queue still reports the old position`() = runBlocking<Unit> {
        val player = playingAt756()

        player.seekToTimeMs(1_000_000)

        assertEquals(FakeMaServer.Command("player_queues/seek", mapOf("queue_id" to KITCHEN, "position" to 1000.0)), server.next())
        assertEquals(1_000_000, player.getCurrentPositionMs())
    }

    @Test
    fun `follows the speaker again once it reports the new position`() = runBlocking<Unit> {
        val player = playingAt756()
        player.seekToTimeMs(754_000)
        assertEquals(754_000, player.getCurrentPositionMs())

        server.pushEdited("event_queue_time_updated") { it["data"] = 754.3 }

        eventually { runBlocking { player.getCurrentPositionMs() } == 754_300 }
    }

    @Test
    fun `measures a second seek from the first seek's target`() = runBlocking<Unit> {
        val player = playingAt756()
        player.seekToTimeMs(1_000_000)
        server.commands.clear()

        player.seekToTimeMs(757_000)

        assertEquals(FakeMaServer.Command("player_queues/seek", mapOf("queue_id" to KITCHEN, "position" to 757.0)), server.next())
    }

    @Test
    fun `forgets a refused seek once someone else starts the queue`() = runBlocking<Unit> {
        val player = handedOff()
        server.push("event_queue_updated_paused")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPaused }
        server.reply("player_queues/seek", "error_invalid_command")
        player.seekToTimeMs(1_000_000)
        assertEquals(1_000_000, player.getCurrentPositionMs())

        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
        server.push("event_queue_updated_paused")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPaused }

        assertEquals(800_000, player.getCurrentPositionMs())
    }

    @Test
    fun `does not send a speed when the item has no duration`() = runBlocking<Unit> {
        server.replyEdited("player_queues/items", "queue_items") { reply -> items(reply).forEach { it["duration"] = null } }
        val player = player()
        player.setEpisode(episodeA)
        player.setPlaybackEffects(PlaybackEffects().apply { playbackSpeed = 1.5 })

        player.play(754_900)

        val handoff = listOf("podcast_episodes", "clear", "play_media", "items", "play_index")
        assertEquals(handoff, generateSequence { server.commands.poll() }.map { it.name.substringAfterLast('/') }.toList())
        server.pushEdited("event_queue_updated_playing") { event ->
            @Suppress("UNCHECKED_CAST")
            val queue = event["data"] as MutableMap<String, Any?>

            @Suppress("UNCHECKED_CAST")
            (queue["current_item"] as MutableMap<String, Any?>)["duration"] = null
        }
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
        player.setPlaybackEffects(PlaybackEffects().apply { playbackSpeed = 2.0 })

        assertTrue(server.commands.isEmpty())
    }

    @Test
    fun `pauses the speaker once a handoff paused midway finishes`() = runBlocking<Unit> {
        val lookup = CompletableDeferred<Unit>()
        val player = player(resolver = gatedResolver(lookup))
        player.setEpisode(episodeA)

        val handoff = async(Dispatchers.Default) { player.play(754_900) }
        eventually { runBlocking { player.isBuffering() } }
        player.pause()
        lookup.complete(Unit)
        handoff.await()

        assertEquals(
            listOf("clear", "play_media", "items", "play_index", "pause").map { "player_queues/$it" },
            queueCommands(),
        )
    }

    @Test
    fun `stops the speaker once a handoff stopped midway finishes`() = runBlocking<Unit> {
        val lookup = CompletableDeferred<Unit>()
        val player = player(resolver = gatedResolver(lookup))
        player.setEpisode(episodeA)

        val handoff = async(Dispatchers.Default) { player.play(754_900) }
        eventually { runBlocking { player.isBuffering() } }
        val stopping = async(Dispatchers.Default) { player.stop() }
        lookup.complete(Unit)
        handoff.await()
        stopping.await()

        assertEquals(
            listOf("clear", "play_media", "items", "play_index", "stop").map { "player_queues/$it" },
            queueCommands(),
        )
        assertNull(store.playback)
    }

    @Test
    fun `controls the speaker it started before Music Assistant confirms the item`() = runBlocking<Unit> {
        val player = handedOff()
        server.commands.clear()

        player.pause()
        player.stop()

        assertEquals(FakeMaServer.Command("player_queues/pause", mapOf("queue_id" to KITCHEN)), server.next())
        assertEquals(FakeMaServer.Command("player_queues/stop", mapOf("queue_id" to KITCHEN)), server.next())
        assertNull(store.playback)
    }

    @Test
    fun `remembers the speaker playback when it cannot reach Music Assistant to stop it`() = runBlocking<Unit> {
        val player = handedOff()
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }

        session.stop()
        player.stop()

        assertEquals(RemotePlayback(KITCHEN, ITEM_A, EPISODE_A), store.playback)
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

    private suspend fun playingAt756(): MusicAssistantPlayer {
        val player = handedOff()
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
        assertEquals(756_700, player.getCurrentPositionMs())
        server.commands.clear()
        return player
    }

    private fun attachedWithUnheardAnnouncement(): MusicAssistantPlayer {
        server.push("event_queue_updated_playing")
        eventually { session.queues.value[KITCHEN]?.currentItem != null }
        listening = false
        val player = player(attachTo = RemotePlayback(KITCHEN, ITEM_A, EPISODE_A))
        eventually { unheard.get() == 1 }
        listening = true
        return player
    }

    @Suppress("UNCHECKED_CAST")
    private fun items(reply: MutableMap<String, Any?>) = reply["result"] as List<MutableMap<String, Any?>>

    private fun gatedResolver(lookup: CompletableDeferred<Unit>) = MaMediaResolver(
        session,
        { uuids ->
            lookup.await()
            sources.filterKeys { it in uuids }
        },
        { GPODDER },
    )

    private fun queueCommands(): List<String> {
        return generateSequence { server.commands.poll() }.map { it.name }.filter { it.startsWith("player_queues/") }.toList()
    }

    private fun player(attachTo: RemotePlayback? = null, resolver: MaMediaResolver = this.resolver) = MusicAssistantPlayer(
        remote = session,
        resolver = resolver,
        playbackStore = store,
        queueId = KITCHEN,
        playerName = "Kitchen",
        scope = scope,
        onPlayerEvent = { _: Player, event: PlayerEvent -> record(event) },
        attachTo = attachTo,
        onPlaybackFailed = { fellBack = true },
        monotonicMs = {
            clockHook()
            nowMs
        },
    )

    private fun record(event: PlayerEvent) {
        when {
            event == PlayerEvent.BufferingStateChanged -> bufferingSignals.incrementAndGet()
            listening -> events += event
            else -> unheard.incrementAndGet()
        }
    }

    private suspend fun handedOff(): MusicAssistantPlayer {
        val player = player()
        player.setEpisode(episodeA)
        wallNow = NOW - CLOCK_OFFSET
        player.play(754_900)
        wallNow = NOW
        events.clear()
        return player
    }

    private companion object {
        const val NOW = 1_792_000_000.0
        const val CLOCK_OFFSET = 2.5
        const val KITCHEN = "media_player.kitchen"
        const val GPODDER = "gpodder--Xk3dTq9a"
        const val FEED = "https://pod.example.com/feeds/show.xml"
        const val EPISODE_A = "episode-a"
        const val URL_A = "https://cdn.example.com/a.mp3"
        const val ITEM_A = "5b2f0e8c9d6a4f1b8e3c7a2d1f0e9b8a"
        const val URI_A = "$GPODDER://podcast_episode/$FEED https://pod.example.com/feeds/ep-a"
    }
}
