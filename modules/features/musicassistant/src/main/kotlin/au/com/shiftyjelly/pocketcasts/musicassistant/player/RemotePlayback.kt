package au.com.shiftyjelly.pocketcasts.musicassistant.player

import au.com.shiftyjelly.pocketcasts.musicassistant.mirror.MirroredItem
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemotePlayback(
    @Json(name = "queue_id") val queueId: String,
    val items: List<MirroredItem>,
)

interface RemotePlaybackStore {
    fun load(): RemotePlayback?

    fun save(playback: RemotePlayback?)
}
