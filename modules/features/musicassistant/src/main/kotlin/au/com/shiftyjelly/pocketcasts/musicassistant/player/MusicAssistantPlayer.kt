package au.com.shiftyjelly.pocketcasts.musicassistant.player

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaCommandException
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaRemote
import au.com.shiftyjelly.pocketcasts.musicassistant.media.MaMedia
import au.com.shiftyjelly.pocketcasts.musicassistant.media.MaMediaResolver
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaQueue
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaQueueItem
import au.com.shiftyjelly.pocketcasts.repositories.playback.Player
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlayerEvent
import com.squareup.moshi.JsonDataException
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MusicAssistantPlayer(
    private val remote: MaRemote,
    private val resolver: MaMediaResolver,
    private val playbackStore: RemotePlaybackStore,
    private val queueId: String,
    private val playerName: String,
    scope: CoroutineScope,
    override val onPlayerEvent: (Player, PlayerEvent) -> Unit,
    attachTo: RemotePlayback? = null,
    private val onPlaybackFailed: () -> Unit = {},
) : Player {
    private data class Target(val episodeUuid: String, val queueItemId: String, val confirmed: Boolean)

    @Volatile
    private var episode: BaseEpisode? = null

    @Volatile
    private var target: Target? = attachTo?.takeIf { it.queueId == queueId }?.let { Target(it.episodeUuid, it.queueItemId, confirmed = false) }

    @Volatile
    private var attachAnnounced = target == null

    @Volatile
    private var lastState: String? = null

    private val queueUpdates = scope.launch {
        remote.queues
            .map { it[queueId] }
            .filterNotNull()
            .distinctUntilChanged()
            .collect(::onQueue)
    }

    override val isRemote: Boolean = true
    override val isStreaming: Boolean = true
    override val filePath: String? = null
    override val isDownloading: Boolean = false
    override val name: String get() = playerName
    override val url: String? get() = episode?.downloadUrl
    override val episodeUuid: String? get() = episode?.uuid

    override fun setEpisode(episode: BaseEpisode, preferStream: Boolean) {
        this.episode = episode
    }

    override fun setPodcast(podcast: Podcast?) = Unit

    override suspend fun load(currentPositionMs: Int) = Unit

    override suspend fun play(currentPositionMs: Int) {
        val current = episode ?: return
        if (current !is PodcastEpisode) {
            fail("Music Assistant can only play podcast episodes")
            return
        }
        val queue = currentQueue()
        if (owns(queue)) {
            if (queue?.isPlaying == true) {
                onPlayerEvent(this, PlayerEvent.PlayerPlaying)
            } else if (!send("player_queues/play")) {
                fail("Could not resume on $playerName")
            }
            return
        }
        handOff(current, currentPositionMs)
    }

    override suspend fun pause() {
        if (owns(currentQueue())) {
            send("player_queues/pause")
        }
    }

    override suspend fun stop() {
        queueUpdates.cancel()
        val queue = currentQueue()
        if (owns(queue) && queue?.state != MaQueue.STATE_IDLE) {
            send("player_queues/stop")
        }
        forgetTarget()
    }

    override suspend fun seekToTimeMs(positionMs: Int) {
        val queue = currentQueue()
        if (queue == null || !owns(queue)) {
            return
        }
        if (abs(queue.elapsedAt(remote.serverNow()) * 1000 - positionMs) < SEEK_TOLERANCE_MS) {
            return
        }
        try {
            remote.call("player_queues/seek", mapOf("queue_id" to queueId, "position" to positionMs / 1000))
        } catch (e: MaCommandException) {
            send("player_queues/play_index", "index" to (queue.currentIndex ?: 0), "seek_position" to startSeconds(positionMs))
        } catch (e: IOException) {
            return
        }
    }

    override suspend fun setPlaybackEffects(playbackEffects: PlaybackEffects) {
        val queue = currentQueue()
        if (queue == null || !owns(queue) || (queue.currentItem?.duration ?: 0) <= 0) {
            return
        }
        if (abs(queue.playbackSpeed - playbackEffects.playbackSpeed) > SPEED_TOLERANCE) {
            send("player_queues/set_playback_speed", "speed" to playbackEffects.playbackSpeed)
        }
    }

    override suspend fun getCurrentPositionMs(): Int {
        val queue = currentQueue()
        if (queue == null || !owns(queue)) {
            return -1
        }
        return (queue.elapsedAt(remote.serverNow()) * 1000).toInt()
    }

    override suspend fun isPlaying(): Boolean {
        val queue = currentQueue()
        return owns(queue) && queue?.isPlaying == true
    }

    override suspend fun isBuffering(): Boolean = false

    override suspend fun durationMs(): Int? {
        return episode?.durationMs?.takeIf { it > 0 } ?: currentQueue()?.currentItem?.duration?.times(1000)
    }

    override suspend fun bufferedUpToMs(): Int = 0

    override suspend fun bufferedPercentage(): Int = 0

    override fun supportsTrimSilence(): Boolean = false

    override fun supportsVolumeBoost(): Boolean = false

    override fun supportsVideo(): Boolean = false

    override fun setVolume(volume: Float) = Unit

    private fun currentQueue(): MaQueue? = remote.queues.value[queueId]

    private fun owns(queue: MaQueue?): Boolean {
        val current = target ?: return false
        return queue != null &&
            !queue.ended &&
            queue.currentItem?.queueItemId == current.queueItemId &&
            current.episodeUuid == episode?.uuid
    }

    private suspend fun handOff(episode: PodcastEpisode, positionMs: Int) {
        val media = resolver.resolve(episode)
        if (media == null) {
            fail("This episode has no stream Music Assistant can play")
            return
        }
        val loaded = load(media, positionMs) ||
            (media is MaMedia.ProviderEpisode && resolver.plainUrl(episode)?.let { load(it, positionMs) } == true)
        if (!loaded) {
            fail("Could not play on $playerName")
        }
    }

    private suspend fun load(media: MaMedia, positionMs: Int): Boolean {
        return try {
            remote.call("player_queues/clear", mapOf("queue_id" to queueId))
            remote.call("player_queues/play_media", mapOf("queue_id" to queueId, "media" to listOf(media.uri), "option" to "add"))
            val first = MaJson.decodeList<MaQueueItem>(
                remote.call("player_queues/items", mapOf("queue_id" to queueId, "limit" to 1, "offset" to 0)),
            ).firstOrNull() ?: return false
            target = Target(media.episodeUuid, first.queueItemId, confirmed = false)
            lastState = null
            playbackStore.save(RemotePlayback(queueId, first.queueItemId, media.episodeUuid))
            remote.call("player_queues/play_index", mapOf("queue_id" to queueId, "index" to 0, "seek_position" to startSeconds(positionMs)))
            true
        } catch (e: MaCommandException) {
            forgetTarget()
            false
        } catch (e: IOException) {
            forgetTarget()
            false
        } catch (e: JsonDataException) {
            forgetTarget()
            false
        }
    }

    private fun onQueue(queue: MaQueue) {
        val current = target ?: return
        if (queue.currentItem?.queueItemId != current.queueItemId) {
            if (current.confirmed) {
                forgetTarget()
                onPlayerEvent(this, PlayerEvent.PlayerPaused)
            }
            return
        }
        if (!current.confirmed) {
            target = current.copy(confirmed = true)
            if (!attachAnnounced) {
                attachAnnounced = true
                onPlayerEvent(this, PlayerEvent.RemoteMetadataNotMatched(current.episodeUuid))
                return
            }
        }
        val playing = episode ?: return
        if (playing.uuid != current.episodeUuid) {
            return
        }
        if (queue.ended) {
            forgetTarget()
            onPlayerEvent(this, PlayerEvent.Completion(current.episodeUuid))
            return
        }
        val duration = queue.currentItem.duration ?: 0
        if (playing.durationMs <= 0 && duration > 0) {
            playing.durationMs = duration * 1000
            onPlayerEvent(this, PlayerEvent.DurationAvailable)
        }
        if (queue.state != lastState) {
            lastState = queue.state
            onPlayerEvent(this, if (queue.isPlaying) PlayerEvent.PlayerPlaying else PlayerEvent.PlayerPaused)
        }
    }

    private fun forgetTarget() {
        target = null
        lastState = null
        playbackStore.save(null)
    }

    private suspend fun send(command: String, vararg extra: Pair<String, Any?>): Boolean {
        return try {
            remote.call(command, mapOf("queue_id" to queueId) + extra)
            true
        } catch (e: MaCommandException) {
            false
        } catch (e: IOException) {
            false
        }
    }

    private fun fail(message: String) {
        onPlayerEvent(this, PlayerEvent.PlayerError(message))
        onPlaybackFailed()
    }

    private companion object {
        const val SEEK_TOLERANCE_MS = 2000
        const val SPEED_TOLERANCE = 0.001

        // Music Assistant reads a seek position of 0 as "resume where Music Assistant last stopped".
        fun startSeconds(positionMs: Int): Int = (positionMs / 1000).coerceAtLeast(1)
    }
}
