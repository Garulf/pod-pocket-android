package au.com.shiftyjelly.pocketcasts.musicassistant.player

import au.com.shiftyjelly.pocketcasts.musicassistant.mirror.MirroredItem
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemotePlayback(
    @Json(name = "queue_id") val queueId: String,
    val items: List<MirroredItem>,
    @Json(name = "stop_owed") val stopOwed: Boolean = false,
)

interface RemotePlaybackStore {
    /** Returns null, and deletes the stored record, when the record cannot be read or comes from an older version. */
    fun load(): RemotePlayback?

    fun save(playback: RemotePlayback?)
}
