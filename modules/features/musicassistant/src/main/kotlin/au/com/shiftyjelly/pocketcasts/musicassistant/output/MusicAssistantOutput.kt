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
    private val notify: (OutputNotice) -> Unit,
) : RemoteOutput {
    private val _activePlayerId = MutableStateFlow<String?>(null)
    val activePlayerId: StateFlow<String?> = _activePlayerId.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    @Volatile
    var routes: OutputRoutes? = null

    @Volatile
    private var listener: CastManager.SessionListener? = null

    @Volatile
    private var undelivered: ((CastManager.SessionListener) -> Unit)? = null

    @Volatile
    private var pendingAttach: RemotePlayback? = null

    @Volatile
    private var heldPlayer: MusicAssistantPlayer? = null

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
        this.listener = listener
        if (listener != null) {
            undelivered?.invoke(listener)
            undelivered = null
        }
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
            onPlaybackFailed = { scope.launch { handBackAfterFailure(name) } },
            onStopped = { releaseHeld(created) },
        )
        heldPlayer = created
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
        _isActive.value = true
        val attaching = pendingAttach?.queueId == playerId
        if (!attaching) {
            pendingAttach = null
        }
        deliver { if (attaching) it.sessionReconnected() else it.sessionStarted() }
    }

    fun onRouteUnselected(playerId: String) {
        if (_activePlayerId.value != playerId) {
            return
        }
        _activePlayerId.value = null
        _isActive.value = false
        pendingAttach = null
        deliver { it.sessionEnded() }
    }

    private fun selectPhone() {
        val current = _activePlayerId.value ?: return
        routes?.selectPhone() ?: onRouteUnselected(current)
    }

    private fun handBackAfterFailure(playerName: String) {
        notify(OutputNotice.PlaybackFailed(playerName))
        selectPhone()
    }

    private fun releaseHeld(player: MusicAssistantPlayer) {
        if (heldPlayer === player) {
            heldPlayer = null
        }
    }

    private fun nudgeUntilAdopted(player: MusicAssistantPlayer, playerId: String) {
        scope.launch {
            repeat(MAX_ATTACH_NUDGES) {
                delay(ATTACH_NUDGE_DELAY_MS)
                if (heldPlayer !== player || _activePlayerId.value != playerId || player.episodeUuid != null) {
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

    private fun attachIfPlaying() {
        if (_activePlayerId.value != null || heldPlayer != null) {
            return
        }
        val stored = playbackStore.load() ?: return
        val queue = session.queues.value[stored.queueId]
        if (queue == null || queue.ended || queue.currentItem?.queueItemId != stored.queueItemId) {
            playbackStore.save(null)
            return
        }
        pendingAttach = stored
        notify(OutputNotice.PlayingOn(playerName(stored.queueId)))
        routes?.selectPlayer(stored.queueId) ?: onRouteSelected(stored.queueId)
    }

    private fun playerName(playerId: String): String = session.players.value[playerId]?.name ?: playerId

    private fun deliver(signal: (CastManager.SessionListener) -> Unit) {
        val current = listener
        if (current == null) {
            undelivered = signal
        } else {
            signal(current)
        }
    }

    private companion object {
        const val MAX_ATTACH_NUDGES = 3
        const val ATTACH_NUDGE_DELAY_MS = 2_500L
    }
}
