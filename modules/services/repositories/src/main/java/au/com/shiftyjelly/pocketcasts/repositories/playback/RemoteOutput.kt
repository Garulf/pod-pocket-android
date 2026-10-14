package au.com.shiftyjelly.pocketcasts.repositories.playback

import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import kotlinx.coroutines.flow.StateFlow

interface RemoteOutput {
    val isActive: StateFlow<Boolean>

    fun createPlayer(onPlayerEvent: (Player, PlayerEvent) -> Unit): Player

    fun setSessionListener(listener: CastManager.SessionListener?)

    suspend fun isPlaying(): Boolean

    suspend fun endSession()
}
