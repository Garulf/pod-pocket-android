package au.com.shiftyjelly.pocketcasts.musicassistant.output

import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.media.MaMediaResolver
import au.com.shiftyjelly.pocketcasts.musicassistant.player.MusicAssistantPlayer
import au.com.shiftyjelly.pocketcasts.musicassistant.player.RemotePlayback
import au.com.shiftyjelly.pocketcasts.musicassistant.player.RemotePlaybackStore
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.Player
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlayerEvent
import au.com.shiftyjelly.pocketcasts.repositories.playback.RemoteOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MusicAssistantOutput(
    private val session: MaSession,
    private val resolver: MaMediaResolver,
    private val playbackStore: RemotePlaybackStore,
    private val scope: CoroutineScope,
    private val heldPlayerGraceMs: Long = HELD_PLAYER_GRACE_MS,
    private val signals: RemoteSessionSignals = RemoteSessionSignals(),
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
        val attach = pendingAttach?.takeIf { it.queueId == playerId }
        pendingAttach = null
        val name = playerName(playerId)
        lateinit var created: MusicAssistantPlayer
        created = MusicAssistantPlayer(
            remote = session,
            resolver = resolver,
            playbackStore = playbackStore,
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
            else -> Unit
        }
    }

    internal fun attachIfPlaying() {
        val stored = synchronized(attachLock) {
            if (_activePlayerId.value != null || heldPlayer != null) {
                return
            }
            val stored = playbackStore.load() ?: return
            val queue = session.queues.value[stored.queueId]
            if (queue == null || queue.ended || queue.currentItem?.queueItemId != stored.items.firstOrNull()?.queueItemId) {
                playbackStore.save(null)
                return
            }
            if (pendingAttach == stored) {
                return
            }
            pendingAttach = stored
            stored
        }
        routes?.selectPlayer(stored.queueId) ?: onRouteSelected(stored.queueId)
    }

    private fun playerName(playerId: String): String = session.players.value[playerId]?.name ?: playerId

    private companion object {
        const val MAX_ATTACH_NUDGES = 3
        const val ATTACH_NUDGE_DELAY_MS = 2_500L
        const val HELD_PLAYER_GRACE_MS = 3 * 60_000L
    }
}
