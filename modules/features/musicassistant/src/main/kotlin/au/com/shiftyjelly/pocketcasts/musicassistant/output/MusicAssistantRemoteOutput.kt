package au.com.shiftyjelly.pocketcasts.musicassistant.output

import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.Player
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlayerEvent
import au.com.shiftyjelly.pocketcasts.repositories.playback.RemoteOutput
import dagger.Lazy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteSessionSignals {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val lock = Any()
    private var listener: CastManager.SessionListener? = null
    private var undelivered: ((CastManager.SessionListener) -> Unit)? = null

    fun setActive(active: Boolean) {
        _isActive.value = active
    }

    fun setSessionListener(listener: CastManager.SessionListener?) {
        val waiting = synchronized(lock) {
            this.listener = listener
            if (listener != null) undelivered.also { undelivered = null } else null
        }
        if (listener != null) {
            waiting?.invoke(listener)
        }
    }

    fun deliver(signal: (CastManager.SessionListener) -> Unit) {
        val current = synchronized(lock) {
            listener.also { if (it == null) undelivered = signal }
        }
        current?.let(signal)
    }
}

class MusicAssistantRemoteOutput(
    private val signals: RemoteSessionSignals,
    private val output: Lazy<MusicAssistantOutput>,
) : RemoteOutput {
    override val isActive: StateFlow<Boolean> = signals.isActive

    override fun setSessionListener(listener: CastManager.SessionListener?) {
        signals.setSessionListener(listener)
    }

    override fun createPlayer(onPlayerEvent: (Player, PlayerEvent) -> Unit): Player {
        return output.get().createPlayer(onPlayerEvent)
    }

    override suspend fun isPlaying(): Boolean {
        return isActive.value && output.get().isPlaying()
    }

    override suspend fun endSession() {
        if (isActive.value) {
            output.get().endSession()
        }
    }
}
