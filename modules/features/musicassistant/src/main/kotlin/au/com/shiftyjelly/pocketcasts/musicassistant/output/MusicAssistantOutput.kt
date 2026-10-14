package au.com.shiftyjelly.pocketcasts.musicassistant.output

import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.media.MaMediaResolver
import au.com.shiftyjelly.pocketcasts.musicassistant.mirror.EpisodeFinisher
import au.com.shiftyjelly.pocketcasts.musicassistant.mirror.UpNextEpisodes
import au.com.shiftyjelly.pocketcasts.musicassistant.player.MusicAssistantPlayer
import au.com.shiftyjelly.pocketcasts.musicassistant.player.RemotePlayback
import au.com.shiftyjelly.pocketcasts.musicassistant.player.RemotePlaybackStore
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.Player
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlayerEvent
import au.com.shiftyjelly.pocketcasts.repositories.playback.RemoteOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

class MusicAssistantOutput(
    private val session: MaSession,
    private val resolver: MaMediaResolver,
    private val playbackStore: RemotePlaybackStore,
    private val upNext: UpNextEpisodes,
    private val finisher: EpisodeFinisher,
    private val scope: CoroutineScope,
    private val heldPlayerGraceMs: Long = HELD_PLAYER_GRACE_MS,
    private val signals: RemoteSessionSignals = RemoteSessionSignals(),
    podPocketSignedIn: Flow<Boolean> = emptyFlow(),
    private val notify: (OutputNotice) -> Unit,
) : RemoteOutput {
    private val _activePlayerId = MutableStateFlow<String?>(null)
    val activePlayerId: StateFlow<String?> = _activePlayerId.asStateFlow()

    override val isActive: StateFlow<Boolean> = signals.isActive

    private val _needsConnection = MutableStateFlow(false)
    val needsConnection: StateFlow<Boolean> = _needsConnection.asStateFlow()

    @Volatile
    var routes: OutputRoutes? = null

    private val connectionLock = Any()

    private val attachLock = Any()

    @Volatile
    private var pendingAttach: RemotePlayback? = null

    @Volatile
    private var heldPlayer: MusicAssistantPlayer? = null

    private var heldPlayerGrace: Job? = null
    private var heldPlayerGraceOver = false

    init {
        scope.launch {
            var previous: MaSessionState? = null
            session.state.collect { state ->
                onSessionState(previous, state)
                previous = state
            }
        }
        scope.launch {
            podPocketSignedIn.distinctUntilChanged().drop(1).filter { signedIn -> !signedIn }.collect { forgetStoredPlayback() }
        }
    }

    override fun setSessionListener(listener: CastManager.SessionListener?) {
        signals.setSessionListener(listener)
    }

    override suspend fun isPlaying(): Boolean {
        val id = _activePlayerId.value ?: return false
        return session.queues.value[id]?.isPlaying == true
    }

    override fun createPlayer(onPlayerEvent: (Player, PlayerEvent) -> Unit): Player {
        val playerId = checkNotNull(_activePlayerId.value) { "No Music Assistant player is selected" }
        val attach = synchronized(attachLock) {
            val pending = pendingAttach?.takeIf { it.queueId == playerId }
            pendingAttach = null
            pending?.let(::reconcile)?.also { finishLater(it.played) }?.playback
        }
        val name = playerName(playerId)
        lateinit var created: MusicAssistantPlayer
        created = MusicAssistantPlayer(
            remote = session,
            resolver = resolver,
            playbackStore = playbackStore,
            upNext = upNext,
            finisher = finisher,
            queueId = playerId,
            playerName = name,
            scope = scope,
            onPlayerEvent = onPlayerEvent,
            attachTo = attach,
            onPlaybackFailed = { handBackAfterFailure(created, playerId, name) },
            onStopped = { releaseHeld(created) },
        )
        heldPlayer = created
        refreshNeedsConnection()
        if (attach != null) {
            nudgeUntilAdopted(created, playerId)
        }
        return created
    }

    override suspend fun endSession() {
        selectPhone()
    }

    fun onRouteSelected(playerId: String) {
        if (_activePlayerId.value == playerId) {
            return
        }
        _activePlayerId.value?.let(::onRouteUnselected)
        _activePlayerId.value = playerId
        signals.setActive(true)
        refreshNeedsConnection()
        val attaching = pendingAttach?.queueId == playerId && heldPlayer == null
        if (!attaching) {
            pendingAttach = null
        }
        signals.deliver { if (attaching) it.sessionReconnected() else it.sessionStarted() }
        if (attaching) {
            notify(OutputNotice.PlayingOn(playerName(playerId)))
        }
    }

    fun onRouteUnselected(playerId: String) {
        if (_activePlayerId.value != playerId) {
            return
        }
        _activePlayerId.value = null
        signals.setActive(false)
        pendingAttach = null
        refreshNeedsConnection()
        signals.deliver { it.sessionEnded() }
    }

    fun onRouteSelectionAbandoned(playerId: String) {
        synchronized(attachLock) {
            if (pendingAttach?.queueId == playerId && _activePlayerId.value != playerId) {
                pendingAttach = null
            }
        }
    }

    private fun selectPhone() {
        selectPhone(_activePlayerId.value ?: return)
    }

    private fun selectPhone(playerId: String) {
        routes?.selectPhone(playerId) ?: onRouteUnselected(playerId)
    }

    private fun handBackAfterFailure(failed: MusicAssistantPlayer, playerId: String, playerName: String) {
        if (!stillOnSpeaker(failed, playerId)) {
            return
        }
        scope.launch {
            if (stillOnSpeaker(failed, playerId)) {
                notify(OutputNotice.PlaybackFailed(playerName))
                selectPhone(playerId)
            }
        }
    }

    private fun stillOnSpeaker(player: MusicAssistantPlayer, playerId: String): Boolean {
        return _activePlayerId.value == playerId && heldPlayer === player
    }

    private fun releaseHeld(player: MusicAssistantPlayer) {
        if (heldPlayer === player) {
            heldPlayer = null
            refreshNeedsConnection()
        }
    }

    private fun refreshNeedsConnection() {
        synchronized(connectionLock) {
            val active = _activePlayerId.value != null
            if (active || heldPlayer == null) {
                heldPlayerGrace?.cancel()
                heldPlayerGrace = null
                heldPlayerGraceOver = false
            } else if (heldPlayerGrace == null && !heldPlayerGraceOver) {
                heldPlayerGrace = endHeldPlayerGraceLater()
            }
            _needsConnection.value = active || (heldPlayer != null && !heldPlayerGraceOver)
        }
    }

    private fun endHeldPlayerGraceLater(): Job = scope.launch {
        delay(heldPlayerGraceMs)
        synchronized(connectionLock) {
            if (heldPlayerGrace !== coroutineContext[Job]) {
                return@launch
            }
            heldPlayerGrace = null
            heldPlayerGraceOver = true
        }
        refreshNeedsConnection()
    }

    private fun nudgeUntilAdopted(player: MusicAssistantPlayer, playerId: String) {
        scope.launch {
            repeat(MAX_ATTACH_NUDGES) {
                delay(ATTACH_NUDGE_DELAY_MS)
                if (!stillOnSpeaker(player, playerId) || player.episodeUuid != null) {
                    return@launch
                }
                player.isPlaying()
            }
        }
    }

    private fun onSessionState(previous: MaSessionState?, state: MaSessionState) {
        when (state) {
            is MaSessionState.Connected -> attachIfPlaying()
            is MaSessionState.Reconnecting -> if (_activePlayerId.value != null && previous is MaSessionState.Connected) notify(OutputNotice.Reconnecting)
            MaSessionState.SignInRequired -> if (previous != MaSessionState.SignInRequired) notify(OutputNotice.SignInRequired)
            MaSessionState.NotConfigured -> forgetStoredPlayback()
            else -> Unit
        }
    }

    internal fun attachIfPlaying() {
        val reconciled = synchronized(attachLock) {
            if (_activePlayerId.value != null || heldPlayer != null) {
                return
            }
            val reconciled = playbackStore.load()?.let(::reconcile) ?: run {
                pendingAttach = null
                return
            }
            if (pendingAttach == reconciled.playback) {
                return
            }
            pendingAttach = reconciled.playback
            reconciled
        }
        val attach = reconciled.playback
        if (reconciled.played.isEmpty()) {
            selectPlayer(attach.queueId)
            return
        }
        scope.launch {
            finish(reconciled.played)
            val stillWanted = synchronized(attachLock) {
                pendingAttach === attach && _activePlayerId.value == null && heldPlayer == null
            }
            if (stillWanted) {
                selectPlayer(attach.queueId)
            }
        }
    }

    private fun forgetStoredPlayback() {
        synchronized(attachLock) {
            pendingAttach = null
            playbackStore.save(null)
        }
    }

    private fun reconcile(stored: RemotePlayback): Reconciled? {
        val queue = session.queues.value[stored.queueId]
        val current = stored.items.indexOfFirst { it.queueItemId == queue?.currentItem?.queueItemId }
        if (stored.stopOwed) {
            playbackStore.save(null)
            if (queue != null && current >= 0 && !queue.ended) {
                stopLater(stored.queueId)
            }
            return null
        }
        if (queue == null || current < 0) {
            playbackStore.save(null)
            return null
        }
        if (queue.ended) {
            playbackStore.save(null)
            finishLater(stored.items.take(current + 1).map { it.episodeUuid })
            return null
        }
        if (current == 0) {
            return Reconciled(stored, played = emptyList())
        }
        val trimmed = stored.copy(items = stored.items.drop(current))
        playbackStore.save(trimmed)
        return Reconciled(trimmed, played = stored.items.take(current).map { it.episodeUuid })
    }

    private fun stopLater(queueId: String) {
        scope.launch {
            try {
                session.call("player_queues/stop", mapOf("queue_id" to queueId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Could not stop the speaker this phone left playing: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun finishLater(episodeUuids: List<String>) {
        if (episodeUuids.isNotEmpty()) {
            scope.launch { finish(episodeUuids) }
        }
    }

    private suspend fun finish(episodeUuids: List<String>) {
        try {
            if (withTimeoutOrNull(FINISH_TIMEOUT_MS) { finisher.finish(episodeUuids) } == null) {
                Timber.w("${finisher::class.java.name} did not finish the episodes Music Assistant played while the app was away within $FINISH_TIMEOUT_MS ms")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not finish the episodes Music Assistant played while the app was away")
        }
    }

    private fun selectPlayer(playerId: String) {
        routes?.selectPlayer(playerId) ?: onRouteSelected(playerId)
    }

    private class Reconciled(val playback: RemotePlayback, val played: List<String>)

    private fun playerName(playerId: String): String = session.players.value[playerId]?.name ?: playerId

    private companion object {
        const val MAX_ATTACH_NUDGES = 3
        const val ATTACH_NUDGE_DELAY_MS = 2_500L
        const val HELD_PLAYER_GRACE_MS = 3 * 60_000L
        const val FINISH_TIMEOUT_MS = 10_000L
    }
}
