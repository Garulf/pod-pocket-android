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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

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
    private val monotonicMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : Player {
    private data class Target(val episodeUuid: String, val queueItemId: String, val confirmed: Boolean)

    private class SeekBaseline(val positionMs: Int, val serverSeconds: Double) {
        fun outdates(queue: MaQueue): Boolean = queue.elapsedTimeLastUpdated < serverSeconds
    }

    private enum class Load { Loaded, MediaRejected, Failed }

    @Volatile
    private var episode: BaseEpisode? = null

    @Volatile
    private var target: Target? = attachTo?.takeIf { it.queueId == queueId }?.let { Target(it.episodeUuid, it.queueItemId, confirmed = false) }

    @Volatile
    private var attachedUuid: String? = target?.episodeUuid

    private val announcements = Any()
    private var announcementCount = 0
    private var capLogged = false
    private var lastAnnouncedAtMs: Long? = null

    @Volatile
    private var lastState: String? = null

    @Volatile
    private var buffering = false

    @Volatile
    private var pendingSeekMs: Int? = null

    @Volatile
    private var lastPositionMs = 0

    private val seekBaseline = AtomicReference<SeekBaseline?>(null)

    @Volatile
    private var speed = 1.0

    private val operations = Mutex()

    override val isRemote: Boolean = true
    override val isStreaming: Boolean = true
    override val filePath: String? = null
    override val isDownloading: Boolean = false
    override val name: String get() = playerName
    override val url: String? get() = episode?.downloadUrl
    override val episodeUuid: String? get() = episode?.uuid

    private val queueUpdates = scope.launch {
        remote.queues
            .map { it[queueId] }
            .distinctUntilChanged()
            .collect(::onQueue)
    }

    override fun setEpisode(episode: BaseEpisode, preferStream: Boolean) {
        this.episode = episode
        attachedUuid = null
    }

    override fun setPodcast(podcast: Podcast?) = Unit

    override suspend fun load(currentPositionMs: Int) {
        reannounceAttach()
    }

    override suspend fun play(currentPositionMs: Int) = operations.withLock {
        val current = episode ?: return@withLock
        if (current !is PodcastEpisode) {
            fail("Music Assistant can only play podcast episodes")
            return@withLock
        }
        val queue = currentQueue()
        if (owns(queue)) {
            resume(queue)
        } else {
            handOff(current, currentPositionMs)
        }
    }

    override suspend fun pause() {
        if (owns(currentQueue())) {
            send("player_queues/pause")
        }
    }

    override suspend fun stop() = operations.withLock {
        queueUpdates.cancel()
        val queue = currentQueue()
        if (owns(queue) && queue?.state != MaQueue.STATE_IDLE) {
            send("player_queues/stop")
        }
        forgetTarget()
        setBuffering(false)
    }

    override suspend fun seekToTimeMs(positionMs: Int) = operations.withLock { seek(positionMs) }

    private suspend fun seek(positionMs: Int) {
        val queue = currentQueue()
        if (queue == null || !owns(queue)) {
            return
        }
        if (abs(estimatedPositionMs(queue, seekBaseline.get()) - positionMs) < SEEK_TOLERANCE_MS) {
            pendingSeekMs = null
            return
        }
        try {
            remote.call("player_queues/seek", mapOf("queue_id" to queueId, "position" to positionMs / 1000))
            pendingSeekMs = null
            rebase(positionMs)
        } catch (e: MaCommandException) {
            if (queue.isPlaying) {
                restartAt(queue, positionMs)
            } else {
                pendingSeekMs = positionMs
            }
        } catch (e: IOException) {
            return
        }
    }

    override suspend fun setPlaybackEffects(playbackEffects: PlaybackEffects) {
        speed = playbackEffects.playbackSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        val queue = currentQueue()
        if (queue == null || !owns(queue) || (queue.currentItem?.duration ?: 0) <= 0) {
            return
        }
        if (differs(queue.playbackSpeed, speed)) {
            send("player_queues/set_playback_speed", "speed" to speed)
        }
    }

    override suspend fun getCurrentPositionMs(): Int {
        reannounceAttach()
        val queue = currentQueue()
        if (queue == null || !owns(queue)) {
            return -1
        }
        val pending = pendingSeekMs
        if (pending != null && !queue.isPlaying) {
            return pending
        }
        val position = if (remote.isConnected) steadyPosition(queue) else lastPositionMs
        return position.takeIf { it > 0 } ?: -1
    }

    override suspend fun isPlaying(): Boolean {
        reannounceAttach()
        val queue = currentQueue()
        return owns(queue) && queue?.isPlaying == true
    }

    override suspend fun isBuffering(): Boolean = buffering

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

    private fun estimatedPositionMs(queue: MaQueue, baseline: SeekBaseline?): Int {
        val serverNow = remote.serverNow()
        if (baseline == null || !baseline.outdates(queue)) {
            return (queue.elapsedAt(serverNow) * 1000).toInt()
        }
        val runningSeconds = if (queue.isPlaying) (serverNow - baseline.serverSeconds).coerceAtLeast(0.0) * queue.playbackSpeed else 0.0
        return baseline.positionMs + (runningSeconds * 1000).toInt()
    }

    private fun steadyPosition(queue: MaQueue): Int {
        val baseline = seekBaseline.get()
        val seekUnheard = baseline != null && baseline.outdates(queue)
        if (baseline != null && !seekUnheard) {
            seekBaseline.compareAndSet(baseline, null)
        }
        val positionMs = estimatedPositionMs(queue, baseline)
        val steppedBack = lastPositionMs - positionMs in 1 until BACKSTEP_TOLERANCE_MS
        val position = if (steppedBack && !seekUnheard) lastPositionMs else positionMs
        lastPositionMs = position
        return position
    }

    private fun rebase(positionMs: Int) {
        seekBaseline.set(SeekBaseline(positionMs, remote.serverNow()))
        lastPositionMs = positionMs
    }

    private suspend fun resume(queue: MaQueue?) {
        val pending = pendingSeekMs
        pendingSeekMs = null
        when {
            queue?.isPlaying == true -> onPlayerEvent(this, PlayerEvent.PlayerPlaying)
            pending != null -> if (queue == null || !restartAt(queue, pending)) fail("Could not resume on $playerName")
            !send("player_queues/play") -> fail("Could not resume on $playerName")
        }
    }

    private suspend fun restartAt(queue: MaQueue, positionMs: Int): Boolean {
        val restarted = send("player_queues/play_index", "index" to (queue.currentIndex ?: 0), "seek_position" to startSeconds(positionMs))
        if (restarted) {
            rebase(positionMs)
        }
        return restarted
    }

    private suspend fun handOff(episode: PodcastEpisode, positionMs: Int) {
        forgetTarget()
        setBuffering(true)
        try {
            val media = resolver.resolve(episode)
            if (media == null) {
                fail("This episode has no stream Music Assistant can play")
                return
            }
            var loaded = load(media, positionMs)
            val plain = if (loaded == Load.MediaRejected && media is MaMedia.ProviderEpisode) resolver.plainUrl(episode) else null
            if (plain != null) {
                loaded = load(plain, positionMs)
            }
            if (loaded != Load.Loaded) {
                fail("Could not play on $playerName")
            }
        } catch (e: CancellationException) {
            setBuffering(false)
            throw e
        }
    }

    private suspend fun load(media: MaMedia, positionMs: Int): Load {
        var addingMedia = false
        return try {
            remote.call("player_queues/clear", mapOf("queue_id" to queueId))
            addingMedia = true
            remote.call("player_queues/play_media", mapOf("queue_id" to queueId, "media" to listOf(media.uri), "option" to "add"))
            addingMedia = false
            val first = MaJson.decodeList<MaQueueItem>(
                remote.call("player_queues/items", mapOf("queue_id" to queueId, "limit" to 1, "offset" to 0)),
            ).firstOrNull() ?: return Load.MediaRejected
            target = Target(media.episodeUuid, first.queueItemId, confirmed = false)
            lastState = null
            lastPositionMs = positionMs
            playbackStore.save(RemotePlayback(queueId, first.queueItemId, media.episodeUuid))
            remote.call("player_queues/play_index", mapOf("queue_id" to queueId, "index" to 0, "seek_position" to startSeconds(positionMs)))
            rebase(positionMs)
            applySpeed(first)
            Load.Loaded
        } catch (e: MaCommandException) {
            forgetTarget()
            if (addingMedia && e.rejectsMedia) Load.MediaRejected else Load.Failed
        } catch (e: IOException) {
            forgetTarget()
            Load.Failed
        } catch (e: JsonDataException) {
            forgetTarget()
            Load.Failed
        }
    }

    private suspend fun applySpeed(item: MaQueueItem) {
        if ((item.duration ?: 0) <= 0) {
            return
        }
        val queueSpeed = currentQueue()?.playbackSpeed ?: 1.0
        if (differs(speed, 1.0) || differs(queueSpeed, speed)) {
            send("player_queues/set_playback_speed", "speed" to speed)
        }
    }

    private fun onQueue(queue: MaQueue?) {
        val current = target ?: return
        val item = queue?.currentItem
        if (queue == null || item == null || item.queueItemId != current.queueItemId) {
            if (current.confirmed) {
                letGo()
            }
            return
        }
        if (!current.confirmed) {
            target = current.copy(confirmed = true)
            setBuffering(false)
            val attached = attachedUuid
            if (attached != null && episode == null) {
                announceAttach(attached, throttled = false)
                return
            }
        }
        if (queue.isPlaying) {
            pendingSeekMs = null
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
        val duration = item.duration ?: 0
        if (playing.durationMs <= 0 && duration > 0) {
            playing.durationMs = duration * 1000
            onPlayerEvent(this, PlayerEvent.DurationAvailable)
        }
        if (queue.state != lastState) {
            lastState = queue.state
            onPlayerEvent(this, if (queue.isPlaying) PlayerEvent.PlayerPlaying else PlayerEvent.PlayerPaused)
        }
    }

    private fun letGo() {
        forgetTarget()
        setBuffering(false)
        onPlayerEvent(this, PlayerEvent.PlayerPaused)
    }

    private fun announceAttach(uuid: String, throttled: Boolean) {
        if (claimAnnouncement(throttled)) {
            onPlayerEvent(this, PlayerEvent.RemoteMetadataNotMatched(uuid))
        }
    }

    private fun claimAnnouncement(throttled: Boolean): Boolean = synchronized(announcements) {
        val last = lastAnnouncedAtMs
        val now = monotonicMs()
        val tooSoon = throttled && last != null && now - last < REANNOUNCE_INTERVAL_MS
        if (announcementCount >= MAX_ATTACH_ANNOUNCEMENTS) {
            if (!capLogged) {
                capLogged = true
                Timber.w("Music Assistant playback on $playerName was not adopted after $MAX_ATTACH_ANNOUNCEMENTS announcements")
            }
            false
        } else if (tooSoon) {
            false
        } else {
            announcementCount++
            lastAnnouncedAtMs = now
            true
        }
    }

    private fun reannounceAttach() {
        val uuid = attachedUuid ?: return
        val current = target ?: return
        if (episode != null || !current.confirmed || currentQueue()?.currentItem?.queueItemId != current.queueItemId) {
            return
        }
        announceAttach(uuid, throttled = true)
    }

    private fun setBuffering(value: Boolean) {
        if (buffering != value) {
            buffering = value
            onPlayerEvent(this, PlayerEvent.BufferingStateChanged)
        }
    }

    private fun forgetTarget() {
        target = null
        attachedUuid = null
        lastState = null
        pendingSeekMs = null
        seekBaseline.set(null)
        lastPositionMs = 0
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
        setBuffering(false)
        onPlayerEvent(this, PlayerEvent.PlayerError(message))
        onPlaybackFailed()
    }

    private val MaCommandException.rejectsMedia: Boolean
        get() = !isAuthFailure && code != MaCommandException.PLAYER_UNAVAILABLE

    private companion object {
        const val SEEK_TOLERANCE_MS = 2000
        const val BACKSTEP_TOLERANCE_MS = 3000
        const val REANNOUNCE_INTERVAL_MS = 2000L
        const val MAX_ATTACH_ANNOUNCEMENTS = 10
        const val NANOS_PER_MILLI = 1_000_000L
        const val SPEED_TOLERANCE = 0.001
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 3.0

        fun differs(first: Double, second: Double): Boolean = abs(first - second) > SPEED_TOLERANCE

        // Music Assistant reads a seek position of 0 as "resume where Music Assistant last stopped".
        fun startSeconds(positionMs: Int): Int = (positionMs / 1000).coerceAtLeast(1)
    }
}
