package au.com.shiftyjelly.pocketcasts.musicassistant.player

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.FakeUpNext
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryPlaybackStore
import au.com.shiftyjelly.pocketcasts.musicassistant.RecordingFinisher
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.awaitValue
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.eventually
import au.com.shiftyjelly.pocketcasts.musicassistant.media.EpisodeSource
import au.com.shiftyjelly.pocketcasts.musicassistant.media.MaMediaResolver
import au.com.shiftyjelly.pocketcasts.musicassistant.mirror.EpisodeFinisher
import au.com.shiftyjelly.pocketcasts.musicassistant.mirror.MirroredItem
import au.com.shiftyjelly.pocketcasts.repositories.playback.Player
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlayerEvent
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

class QueueMirroringTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer().signedIn()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = MaSession(scope, InMemoryAccountStore(account(server.url)), { MaConnection(OkHttpClient()) })
    private val sources = listOf(
        EpisodeSource("a", FEED, guid = null, enclosureUrl = "https://cdn.example.com/a.mp3"),
        EpisodeSource("b", FEED, guid = null, enclosureUrl = "https://cdn.example.com/b.mp3?source=rss&amp=1"),
        EpisodeSource("c", FEED, guid = "has space", enclosureUrl = "https://cdn.example.com/c.mp3"),
    ).associateBy { it.uuid }
    private val resolver = MaMediaResolver(session, { uuids -> sources.filterKeys { it in uuids } }, { GPODDER })
    private val store = InMemoryPlaybackStore()
    private val upNext = FakeUpNext()
    private val finisher = RecordingFinisher()
    private val events = CopyOnWriteArrayList<PlayerEvent>()
    private val order = CopyOnWriteArrayList<String>()
    private val a = episode("a")
    private val b = episode("b")
    private val c = episode("c")

    @Before
    fun setUp() = runBlocking<Unit> {
        server.reply("music/podcasts/podcast_episodes", "podcast_episodes_partial", "podcast_episodes_final")
        server.reply("player_queues/all", "queues_all_idle")
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
    fun `hands the whole Up Next to the speaker`() = runBlocking<Unit> {
        upNext.episodes.value = listOf(a, b, c)
        server.reply("player_queues/items", "queue_items_three")
        val player = player()
        player.setEpisode(a)
        server.commands.clear()

        player.play(60_000)
        server.push("event_queue_updated_playing")

        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_B, URI_C), "option" to "add"), secondPlayMedia().args)
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_B, MIRROR_C) }
    }

    @Test
    fun `leaves out an Up Next episode that has no stream`() = runBlocking<Unit> {
        val local = PodcastEpisode(uuid = "local", publishedDate = Date(), downloadUrl = null)
        upNext.episodes.value = listOf(a, local, b)
        server.reply("player_queues/items", "queue_items")
        val player = player()
        player.setEpisode(a)
        server.commands.clear()

        player.play(60_000)
        server.push("event_queue_updated_playing")

        assertEquals(listOf(URI_B), secondPlayMedia().args["media"])
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_B) }
    }

    @Test
    fun `hands at most 25 episodes to the speaker`() = runBlocking<Unit> {
        val many = (1..30).map { PodcastEpisode(uuid = "e$it", publishedDate = Date(), downloadUrl = "https://cdn.example.com/e$it.mp3") }
        upNext.episodes.value = many
        server.reply("player_queues/items", "queue_items")
        val player = player()
        player.setEpisode(many.first())
        server.commands.clear()

        player.play(0)
        server.push("event_queue_updated_playing")

        assertEquals((2..25).map { "builtin://track/https://cdn.example.com/e$it.mp3" }, secondPlayMedia().args["media"])
    }

    @Test
    fun `follows the speaker to the next episode`() = runBlocking<Unit> {
        handedOff(listOf(a, b, c), "queue_items_three")

        server.push("event_queue_updated_next_item")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertEquals(listOf(MIRROR_B, MIRROR_C), store.playback?.items)
        assertTrue(finisher.finished.isEmpty())
    }

    @Test
    fun `finishes episodes the speaker played while nobody watched`() = runBlocking<Unit> {
        handedOff(listOf(a, b, c), "queue_items_three")

        server.push("event_queue_updated_third_item")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertEquals(listOf("b"), finisher.finished.toList())
        assertEquals(listOf(MIRROR_C), store.playback?.items)
    }

    @Test
    fun `reports the end of the last mirrored episode`() = runBlocking<Unit> {
        val player = handedOff(listOf(a, b), "queue_items")
        server.push("event_queue_updated_next_item")
        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        player.setEpisode(b)

        server.push("event_queue_updated_ended_after_next")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "b" }
    }

    @Test
    fun `finishes the last episode when the speaker ended past it unwatched`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")

        server.push("event_queue_updated_ended_after_next")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertEquals(listOf("b"), finisher.finished.toList())
        assertNull(store.playback)
    }

    @Test
    fun `does not carry a pending seek to the next episode`() = runBlocking<Unit> {
        val player = handedOff(listOf(a, b), "queue_items")
        server.push("event_queue_updated_paused")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPaused }
        server.reply("player_queues/seek", "error_invalid_command")
        player.seekToTimeMs(1_000_000)
        assertEquals(1_000_000, player.getCurrentPositionMs())

        server.pushEdited("event_queue_updated_next_item") { event -> queue(event)["state"] = "paused" }
        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        player.setEpisode(b)
        server.commands.clear()

        assertEquals(-1, player.getCurrentPositionMs())
        player.play(0)
        assertEquals(FakeMaServer.Command("player_queues/play", mapOf("queue_id" to KITCHEN)), server.next())
    }

    @Test
    fun `does not resend an episode the speaker left out`() = runBlocking<Unit> {
        val player = handedOff(listOf(a, b, c), "queue_items", mirrored = listOf(MIRROR_A, MIRROR_B))

        player.play(60_000)

        assertNull(server.commands.firstOrNull { it.name == "player_queues/play_media" })
    }

    @Test
    fun `adds episodes appended to Up Next`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        server.reply("player_queues/items", "queue_items_three")

        upNext.episodes.value = listOf(a, b, c)

        val playMedia = server.nextExcept("music/podcasts/podcast_episodes")
        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_C), "option" to "add"), playMedia.args)
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_B, MIRROR_C) }
    }

    @Test
    fun `plays next an episode put after the current one`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        server.reply("player_queues/items", "queue_items_play_next")

        upNext.episodes.value = listOf(a, c, b)

        val playMedia = server.nextExcept("music/podcasts/podcast_episodes")
        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_C), "option" to "next"), playMedia.args)
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_C, MIRROR_B) }
    }

    @Test
    fun `deletes episodes removed from Up Next`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        server.reply("player_queues/items", "queue_items_current_only")

        upNext.episodes.value = listOf(a)

        assertEquals(mapOf("queue_id" to KITCHEN, "item_id_or_index" to ITEM_B), server.next().args)
        eventually { store.playback?.items == listOf(MIRROR_A) }
    }

    @Test
    fun `rewrites the upcoming items after a move`() = runBlocking<Unit> {
        handedOff(listOf(a, b, c), "queue_items_three")
        server.reply("player_queues/items", "queue_items_play_next")

        upNext.episodes.value = listOf(a, c, b)

        val playMedia = server.nextExcept("music/podcasts/podcast_episodes")
        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_C, URI_B), "option" to "replace_next"), playMedia.args)
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_C, MIRROR_B) }
    }

    @Test
    fun `leaves the queue alone while another episode takes over`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")

        upNext.episodes.value = listOf(c, a, b)
        Thread.sleep(600)

        assertTrue(server.commands.isEmpty())
    }

    @Test
    fun `skips an episode the speaker buffered but Up Next moved`() = runBlocking<Unit> {
        bufferedSecondB()

        server.push("event_queue_updated_next_item")

        assertEquals(FakeMaServer.Command("player_queues/next", mapOf("queue_id" to KITCHEN)), server.next())
        server.push("event_queue_updated_third_item")
        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertEquals(1, events.count { it is PlayerEvent.Completion })
        assertTrue(finisher.finished.isEmpty())
        assertEquals(listOf(MIRROR_C, MIRROR_B2), store.playback?.items)
    }

    @Test
    fun `plays the buffered episode when the speaker cannot skip it`() = runBlocking<Unit> {
        bufferedSecondB()
        server.reply("player_queues/next", "error_invalid_command")

        server.push("event_queue_updated_next_item")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertTrue(finisher.finished.isEmpty())
        assertEquals(listOf(MIRROR_B, MIRROR_C, MIRROR_B2), store.playback?.items)
    }

    @Test
    fun `stops after the last episode when Up Next no longer wants it`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        buffering(1)
        upNext.episodes.value = listOf(a)
        Thread.sleep(600)
        assertTrue(server.commands.isEmpty())

        server.push("event_queue_updated_next_item")

        assertEquals(FakeMaServer.Command("player_queues/stop", mapOf("queue_id" to KITCHEN)), server.next())
        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertTrue(finisher.finished.isEmpty())
        assertNull(store.playback)
    }

    @Test
    fun `keeps following when the speaker refuses to stop after the last episode`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        buffering(1)
        upNext.episodes.value = listOf(a)
        Thread.sleep(600)
        server.reply("player_queues/stop", "error_invalid_command")

        server.push("event_queue_updated_next_item")

        assertEquals("player_queues/stop", server.next().name)
        Thread.sleep(200)
        assertTrue(events.none { it is PlayerEvent.Completion || it == PlayerEvent.PlayerPaused })
        assertEquals(listOf(MIRROR_A, MIRROR_B), store.playback?.items)
        server.reply("player_queues/stop", "command_ok")
        server.pushEdited("event_queue_updated_next_item") { event -> queue(event)["elapsed_time"] = 5.0 }
        assertEquals("player_queues/stop", server.next().name)
        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertNull(store.playback)
    }

    @Test
    fun `stops the speaker on a later mirrored episode while finishing the ones it passed`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val stuck = EpisodeFinisher {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        val player = handedOff(listOf(a, b, c), "queue_items_three", finisher = stuck)
        server.push("event_queue_updated_third_item")
        entered.await()

        player.stop()

        assertEquals(FakeMaServer.Command("player_queues/stop", mapOf("queue_id" to KITCHEN)), server.next())
    }

    @Test
    fun `reports the end when the speaker ends on an episode it is skipping`() = runBlocking<Unit> {
        bufferedSecondB()
        server.push("event_queue_updated_next_item")
        assertEquals("player_queues/next", server.next().name)

        server.push("event_queue_updated_ended_after_next")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertTrue(finisher.finished.isEmpty())
        assertNull(store.playback)
    }

    @Test
    fun `plays an episode put back where the speaker buffered it`() = runBlocking<Unit> {
        bufferedSecondB()
        server.reply("player_queues/items", "queue_items_three")

        upNext.episodes.value = listOf(a, b, c)

        assertEquals(mapOf("queue_id" to KITCHEN, "item_id_or_index" to ITEM_B2), server.nextExcept("music/podcasts/podcast_episodes").args)
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_B, MIRROR_C) }
        server.commands.clear()
        server.push("event_queue_updated_next_item")
        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertNull(server.commands.firstOrNull { it.name == "player_queues/next" })
        assertEquals(listOf(MIRROR_B, MIRROR_C), store.playback?.items)
    }

    @Test
    fun `removes an episode and appends another in one edit`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        server.replyEdited("player_queues/items", "queue_items_play_next") { reply -> reply["result"] = (reply["result"] as List<*>).take(2) }

        upNext.episodes.value = listOf(a, c)

        assertEquals(FakeMaServer.Command("player_queues/delete_item", mapOf("queue_id" to KITCHEN, "item_id_or_index" to ITEM_B)), server.next())
        val playMedia = server.nextExcept("music/podcasts/podcast_episodes")
        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_C), "option" to "add"), playMedia.args)
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_C) }
    }

    @Test
    fun `neither confirms nor lets go while an attached session is on a later item`() = runBlocking<Unit> {
        player(attachTo = RemotePlayback(KITCHEN, listOf(MIRROR_A, MIRROR_B)))

        server.push("event_queue_updated_next_item")
        eventually { session.queues.value[KITCHEN]?.currentItem?.queueItemId == ITEM_B }
        Thread.sleep(300)

        assertTrue(events.isEmpty())
        server.push("event_queue_updated_playing")
        eventually { (events.lastOrNull() as? PlayerEvent.RemoteMetadataNotMatched)?.remoteEpisodeUuid == "a" }
    }

    @Test
    fun `waits for a burst of Up Next edits to settle`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        server.reply("player_queues/items", "queue_items_play_next")

        upNext.episodes.value = listOf(a, b, c)
        Thread.sleep(100)
        upNext.episodes.value = listOf(a, c, b)

        val playMedia = server.nextExcept("music/podcasts/podcast_episodes")
        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_C), "option" to "next"), playMedia.args)
        Thread.sleep(600)
        assertNull(server.commands.firstOrNull { it.name == "player_queues/play_media" })
    }

    @Test
    fun `sends nothing for an Up Next edit after stopping`() = runBlocking<Unit> {
        val player = handedOff(listOf(a, b), "queue_items")
        player.stop()
        server.commands.clear()

        upNext.episodes.value = listOf(a, b, c)
        Thread.sleep(600)

        assertTrue(server.commands.isEmpty())
    }

    @Test
    fun `finishes the episodes it passed before reporting the completion`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val gated = EpisodeFinisher { uuids ->
            entered.complete(Unit)
            gate.await()
            finisher.finished += uuids
            order += "finished"
        }
        handedOff(listOf(a, b, c), "queue_items_three", finisher = gated)

        server.push("event_queue_updated_third_item")
        entered.await()
        Thread.sleep(200)
        assertTrue(events.none { it is PlayerEvent.Completion })
        gate.complete(Unit)

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertEquals(listOf("finished", "completion"), order.toList())
        assertEquals(listOf("b"), finisher.finished.toList())
    }

    @Test
    fun `follows an attached session to its next episode`() = runBlocking<Unit> {
        player(attachTo = RemotePlayback(KITCHEN, listOf(MIRROR_A, MIRROR_B, MIRROR_C)))
        server.push("event_queue_updated_playing")
        eventually { (events.lastOrNull() as? PlayerEvent.RemoteMetadataNotMatched)?.remoteEpisodeUuid == "a" }

        server.push("event_queue_updated_third_item")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertEquals(listOf("b"), finisher.finished.toList())
        assertEquals(listOf(MIRROR_C), store.playback?.items)
    }

    @Test
    fun `keeps the episodes the speaker added while the append timed out`() = runBlocking<Unit> {
        upNext.episodes.value = listOf(a, b, c)
        server.reply("player_queues/items", "queue_items_three")
        server.reply("player_queues/play_media")
        server.replyOnce("player_queues/play_media", "command_ok")
        val player = player(enqueueTimeoutMs = 300)
        player.setEpisode(a)
        player.play(60_000)
        server.push("event_queue_updated_playing")
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_B, MIRROR_C) }
        server.commands.clear()

        player.play(60_000)

        assertNull(server.commands.firstOrNull { it.name == "player_queues/play_media" })
    }

    @Test
    fun `keeps the items added while the speaker moved on mid-edit`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        movesOnDuringTheNextEdit()

        upNext.episodes.value = listOf(a, b, c)

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertEquals(listOf(MIRROR_B, MIRROR_C), store.playback?.items)
        assertTrue(events.none { it == PlayerEvent.PlayerPaused })
    }

    @Test
    fun `does not add the episodes again after the speaker moved on mid-edit`() = runBlocking<Unit> {
        val player = handedOff(listOf(a, b), "queue_items")
        movesOnDuringTheNextEdit()
        upNext.episodes.value = listOf(a, b, c)
        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        player.setEpisode(b)
        server.commands.clear()

        upNext.episodes.value = listOf(b, c)
        Thread.sleep(600)

        assertNull(server.commands.firstOrNull { it.name == "player_queues/play_media" })
    }

    @Test
    fun `follows the speaker past the next episode after it moved on mid-edit`() = runBlocking<Unit> {
        val player = handedOff(listOf(a, b), "queue_items")
        movesOnDuringTheNextEdit()
        upNext.episodes.value = listOf(a, b, c)
        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        player.setEpisode(b)

        server.push("event_queue_updated_third_item")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "b" }
        assertTrue(events.none { it == PlayerEvent.PlayerPaused })
        assertEquals(listOf(MIRROR_C), store.playback?.items)
    }

    @Test
    fun `reads the queue again before letting go when the last read failed`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        server.reply("player_queues/items", "queue_items_three")
        server.replyOnce("player_queues/items", "error_invalid_command")
        upNext.episodes.value = listOf(a, b, c)
        assertEquals("player_queues/items", server.nextExcept("music/podcasts/podcast_episodes", "player_queues/play_media").name)

        server.push("event_queue_updated_third_item")

        eventually { (events.lastOrNull() as? PlayerEvent.Completion)?.episodeUUID == "a" }
        assertEquals(listOf("b"), finisher.finished.toList())
        assertEquals(listOf(MIRROR_C), store.playback?.items)
        assertTrue(events.none { it == PlayerEvent.PlayerPaused })
    }

    @Test
    fun `waits for an idle speaker with a buffered episode before editing its queue`() = runBlocking<Unit> {
        handedOff(listOf(a, b), "queue_items")
        server.pushEdited("event_queue_updated_playing") { event ->
            queue(event)["state"] = "idle"
            queue(event)["index_in_buffer"] = 1
        }
        eventually { session.queues.value[KITCHEN]?.state == "idle" }

        upNext.episodes.value = listOf(a, c, b)
        Thread.sleep(600)
        assertTrue(server.commands.isEmpty())
        buffering(1)

        val playMedia = server.nextExcept("music/podcasts/podcast_episodes", "player_queues/items")
        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_C, URI_B), "option" to "add"), playMedia.args)
    }

    private fun player(
        finisher: EpisodeFinisher = this.finisher,
        attachTo: RemotePlayback? = null,
        enqueueTimeoutMs: Long = 30_000,
    ) = MusicAssistantPlayer(
        remote = session,
        resolver = resolver,
        playbackStore = store,
        upNext = upNext,
        finisher = finisher,
        queueId = KITCHEN,
        playerName = "Kitchen",
        scope = scope,
        onPlayerEvent = { _: Player, event: PlayerEvent ->
            if (event is PlayerEvent.Completion) {
                order += "completion"
            }
            events += event
        },
        attachTo = attachTo,
        enqueueTimeoutMs = enqueueTimeoutMs,
    )

    private suspend fun bufferedSecondB() {
        handedOff(listOf(a, b, c), "queue_items_three")
        buffering(1)
        server.replyEdited("player_queues/items", "queue_items_three") { reply ->
            val items = reply["result"] as List<*>
            @Suppress("UNCHECKED_CAST")
            val secondB = (items[1] as Map<String, Any?>) + ("queue_item_id" to ITEM_B2)
            reply["result"] = items + listOf(secondB)
        }

        upNext.episodes.value = listOf(a, c, b)

        val playMedia = server.nextExcept("music/podcasts/podcast_episodes")
        assertEquals(mapOf("queue_id" to KITCHEN, "media" to listOf(URI_B), "option" to "add"), playMedia.args)
        eventually { store.playback?.items == listOf(MIRROR_A, MIRROR_B, MIRROR_C, MIRROR_B2) }
        server.commands.clear()
    }

    private fun buffering(index: Int) {
        server.pushEdited("event_queue_updated_playing") { event -> queue(event)["index_in_buffer"] = index }
        eventually { session.queues.value[KITCHEN]?.let { it.indexInBuffer == index && it.isPlaying } == true }
    }

    private fun movesOnDuringTheNextEdit() {
        server.replyPaged("player_queues/items", "queue_items_three")
        server.replyEdited("player_queues/play_media", "command_ok") { server.push("event_queue_updated_next_item") }
    }

    private suspend fun handedOff(
        episodes: List<PodcastEpisode>,
        items: String,
        mirrored: List<MirroredItem> = listOf(MIRROR_A, MIRROR_B, MIRROR_C).take(episodes.size),
        finisher: EpisodeFinisher = this.finisher,
    ): MusicAssistantPlayer {
        upNext.episodes.value = episodes
        server.reply("player_queues/items", items)
        val player = player(finisher)
        player.setEpisode(episodes.first())
        player.play(60_000)
        server.push("event_queue_updated_playing")
        eventually { events.lastOrNull() == PlayerEvent.PlayerPlaying }
        eventually { store.playback?.items == mirrored }
        server.commands.clear()
        events.clear()
        order.clear()
        return player
    }

    private fun secondPlayMedia(): FakeMaServer.Command {
        return generateSequence { server.next() }.filter { it.name == "player_queues/play_media" }.elementAt(1)
    }

    @Suppress("UNCHECKED_CAST")
    private fun queue(event: MutableMap<String, Any?>) = event["data"] as MutableMap<String, Any?>

    private fun episode(uuid: String) = PodcastEpisode(uuid = uuid, publishedDate = Date(), downloadUrl = sources.getValue(uuid).enclosureUrl)

    private companion object {
        const val KITCHEN = "media_player.kitchen"
        const val GPODDER = "gpodder--Xk3dTq9a"
        const val FEED = "https://pod.example.com/feeds/show.xml"
        const val ITEM_B = "0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f"
        const val ITEM_B2 = "1f2e3d4c5b6a79881f2e3d4c5b6a7988"
        const val URI_A = "$GPODDER://podcast_episode/$FEED https://pod.example.com/feeds/ep-a"
        const val URI_B = "$GPODDER://podcast_episode/$FEED urn:uuid:7a4f3c1e-0b2d-4e8a-9c5f-2d1e3b4a5c6d"
        const val URI_C = "$GPODDER://podcast_episode/$FEED https://cdn.example.com/c.mp3"
        val MIRROR_A = MirroredItem("a", "5b2f0e8c9d6a4f1b8e3c7a2d1f0e9b8a", URI_A)
        val MIRROR_B = MirroredItem("b", ITEM_B, URI_B)
        val MIRROR_C = MirroredItem("c", "9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b", URI_C)
        val MIRROR_B2 = MirroredItem("b", ITEM_B2, URI_B)
    }
}
