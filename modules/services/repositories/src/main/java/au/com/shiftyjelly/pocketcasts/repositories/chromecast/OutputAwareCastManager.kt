package au.com.shiftyjelly.pocketcasts.repositories.chromecast

import au.com.shiftyjelly.pocketcasts.repositories.playback.RemoteOutput
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class OutputAwareCastManager(
    private val cast: CastManager,
    remoteOutput: Optional<RemoteOutput>,
    scope: CoroutineScope,
) : CastManager {
    private val remote: RemoteOutput? = remoteOutput.orElse(null)
    private val remoteActive: StateFlow<Boolean> = remote?.isActive ?: MutableStateFlow(false)

    override val isConnectedFlow: StateFlow<Boolean> = combine(cast.isConnectedFlow, remoteActive) { casting, remote -> casting || remote }
        .stateIn(scope, SharingStarted.Eagerly, cast.isConnectedFlow.value || remoteActive.value)

    override suspend fun isAvailable(): Boolean = cast.isAvailable()

    override suspend fun isConnected(): Boolean = remoteActive.value || cast.isConnected()

    override suspend fun endSession() {
        if (remoteActive.value) {
            remote?.endSession()
        } else {
            cast.endSession()
        }
    }

    override suspend fun startSessionListener(sessionListener: CastManager.SessionListener) {
        cast.startSessionListener(ChromecastSessionListener(sessionListener))
        remote?.setSessionListener(sessionListener)
    }

    override suspend fun stopSessionListener() {
        cast.stopSessionListener()
        remote?.setSessionListener(null)
    }

    override suspend fun isPlaying(): Boolean {
        return if (remoteActive.value) remote?.isPlaying() == true else cast.isPlaying()
    }

    private inner class ChromecastSessionListener(private val listener: CastManager.SessionListener) : CastManager.SessionListener {
        private val listenerHoldsSession = AtomicBoolean(false)

        override fun sessionStarted() = whileChromecastLeads {
            listenerHoldsSession.set(true)
            listener.sessionStarted()
        }

        override fun sessionEnded() {
            if (listenerHoldsSession.getAndSet(false) || !remoteActive.value) {
                listener.sessionEnded()
            }
        }

        override fun sessionReconnected() = whileChromecastLeads {
            listenerHoldsSession.set(true)
            listener.sessionReconnected()
        }

        override fun sessionFailed(errorCode: Int, failureType: CastManager.SessionFailureType) {
            whileChromecastLeads { listener.sessionFailed(errorCode, failureType) }
        }

        private inline fun whileChromecastLeads(forward: () -> Unit) {
            if (!remoteActive.value) {
                forward()
            }
        }
    }
}
