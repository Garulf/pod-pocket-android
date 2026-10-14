package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MirroredItem(
    @Json(name = "episode_uuid") val episodeUuid: String,
    @Json(name = "queue_item_id") val queueItemId: String,
    val uri: String,
)

sealed interface QueueEdit {
    data object None : QueueEdit

    data object Restart : QueueEdit

    data class Append(val episodeUuids: List<String>) : QueueEdit

    data class PlayNext(val episodeUuids: List<String>) : QueueEdit

    data class Remove(val queueItemIds: List<String>) : QueueEdit

    data class ReplaceUpcoming(val episodeUuids: List<String>) : QueueEdit
}

fun planQueueEdit(mirror: List<MirroredItem>, upNext: List<String>): QueueEdit {
    val mirrored = mirror.map { it.episodeUuid }
    if (mirrored.isEmpty() || upNext.isEmpty() || upNext.first() != mirrored.first()) {
        return QueueEdit.Restart
    }
    if (upNext == mirrored) {
        return QueueEdit.None
    }
    if (upNext.size > mirrored.size && upNext.take(mirrored.size) == mirrored) {
        return QueueEdit.Append(upNext.drop(mirrored.size))
    }
    val inserted = upNext.size - mirrored.size
    if (inserted > 0 && upNext.drop(1 + inserted) == mirrored.drop(1)) {
        return QueueEdit.PlayNext(upNext.subList(1, 1 + inserted))
    }
    if (upNext.size < mirrored.size && mirrored.filter { it in upNext } == upNext) {
        return QueueEdit.Remove(mirror.filter { it.episodeUuid !in upNext }.map { it.queueItemId })
    }
    return QueueEdit.ReplaceUpcoming(upNext.drop(1))
}
