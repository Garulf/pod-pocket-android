package au.com.shiftyjelly.pocketcasts.musicassistant.player

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemotePlayback(
    @Json(name = "queue_id") val queueId: String,
    @Json(name = "queue_item_id") val queueItemId: String,
    @Json(name = "episode_uuid") val episodeUuid: String,
)

interface RemotePlaybackStore {
    fun load(): RemotePlayback?

    fun save(playback: RemotePlayback?)
}
