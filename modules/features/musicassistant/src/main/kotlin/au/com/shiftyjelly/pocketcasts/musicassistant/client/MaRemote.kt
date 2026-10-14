package au.com.shiftyjelly.pocketcasts.musicassistant.client

import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaPlayer
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaQueue
import kotlinx.coroutines.flow.StateFlow

interface MaRemote {
    val players: StateFlow<Map<String, MaPlayer>>
    val queues: StateFlow<Map<String, MaQueue>>

    suspend fun call(command: String, args: Map<String, Any?> = emptyMap()): Any?

    fun serverNow(): Double
}
