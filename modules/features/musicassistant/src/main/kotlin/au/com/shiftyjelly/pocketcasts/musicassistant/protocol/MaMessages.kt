package au.com.shiftyjelly.pocketcasts.musicassistant.protocol

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MaServerInfo(
    @Json(name = "server_id") val serverId: String,
    @Json(name = "server_version") val serverVersion: String,
    @Json(name = "schema_version") val schemaVersion: Int,
    val name: String? = null,
)

@JsonClass(generateAdapter = true)
data class MaLoginResult(
    val success: Boolean,
    @Json(name = "access_token") val accessToken: String? = null,
    val error: String? = null,
)

@JsonClass(generateAdapter = true)
data class MaProvider(
    val type: String,
    val domain: String,
    val name: String,
    @Json(name = "instance_id") val instanceId: String,
    val available: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class MaPlayer(
    @Json(name = "player_id") val playerId: String,
    val name: String,
    val type: String,
    val available: Boolean,
    val enabled: Boolean = true,
    @Json(name = "hide_in_ui") val hideInUi: Boolean = false,
    @Json(name = "synced_to") val syncedTo: String? = null,
    @Json(name = "supported_features") val supportedFeatures: List<String> = emptyList(),
    @Json(name = "volume_level") val volumeLevel: Int? = null,
) {
    val canSetVolume: Boolean get() = "volume_set" in supportedFeatures
}

@JsonClass(generateAdapter = true)
data class MaQueue(
    @Json(name = "queue_id") val queueId: String,
    val active: Boolean = false,
    val available: Boolean = true,
    val items: Int = 0,
    @Json(name = "current_index") val currentIndex: Int? = null,
    @Json(name = "elapsed_time") val elapsedTime: Double = 0.0,
    @Json(name = "elapsed_time_last_updated") val elapsedTimeLastUpdated: Double = 0.0,
    @Json(name = "playback_speed") val playbackSpeed: Double = 1.0,
    val state: String = STATE_IDLE,
    @Json(name = "current_item") val currentItem: MaQueueItem? = null,
    @Json(name = "next_item") val nextItem: MaQueueItem? = null,
    val ended: Boolean = false,
) {
    val isPlaying: Boolean get() = state == STATE_PLAYING

    fun elapsedAt(serverNow: Double): Double {
        val running = if (isPlaying && elapsedTimeLastUpdated > 0.0) (serverNow - elapsedTimeLastUpdated).coerceAtLeast(0.0) * playbackSpeed else 0.0
        return (elapsedTime + running).coerceAtLeast(0.0)
    }

    companion object {
        const val STATE_PLAYING = "playing"
        const val STATE_PAUSED = "paused"
        const val STATE_IDLE = "idle"
    }
}

@JsonClass(generateAdapter = true)
data class MaQueueItem(
    @Json(name = "queue_item_id") val queueItemId: String,
    val name: String = "",
    val duration: Int? = null,
    @Json(name = "media_item") val mediaItem: MaMediaItem? = null,
)

@JsonClass(generateAdapter = true)
data class MaMediaItem(
    @Json(name = "item_id") val itemId: String,
    val provider: String,
    val uri: String? = null,
    @Json(name = "media_type") val mediaType: String = "unknown",
    val name: String = "",
    val duration: Int? = null,
    @Json(name = "provider_mappings") val providerMappings: List<MaProviderMapping> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class MaProviderMapping(
    @Json(name = "item_id") val itemId: String,
    @Json(name = "provider_instance") val providerInstance: String,
    val url: String? = null,
)
