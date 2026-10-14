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

    data class RemoveAndAppend(val queueItemIds: List<String>, val episodeUuids: List<String>) : QueueEdit

    data class ReplaceUpcoming(val episodeUuids: List<String>) : QueueEdit
}

data class QueuePlan(
    val edit: QueueEdit,
    val skipOnArrival: List<String> = emptyList(),
)

fun planQueueEdit(mirror: List<MirroredItem>, upNext: List<String>, buffered: Int): QueuePlan {
    if (mirror.isEmpty() || upNext.isEmpty() || upNext.first() != mirror.first().episodeUuid) {
        return QueuePlan(QueueEdit.Restart)
    }
    val bufferedCount = buffered.coerceIn(0, mirror.size - 1)
    val wanted = upNext.drop(1)

    var keptBuffered = 0
    val skipOnArrival = mutableListOf<String>()
    mirror.drop(1).take(bufferedCount).forEach { item ->
        if (wanted.getOrNull(keptBuffered) == item.episodeUuid) {
            keptBuffered++
        } else {
            skipOnArrival += item.queueItemId
        }
    }

    val edit = planEditableTail(mirror.drop(1 + bufferedCount), wanted.drop(keptBuffered))
    return QueuePlan(edit, skipOnArrival)
}

private fun planEditableTail(tail: List<MirroredItem>, wanted: List<String>): QueueEdit {
    val tailUuids = tail.map { it.episodeUuid }
    if (wanted == tailUuids) {
        return QueueEdit.None
    }
    if (wanted.size > tailUuids.size) {
        if (wanted.take(tailUuids.size) == tailUuids) {
            return QueueEdit.Append(wanted.drop(tailUuids.size))
        }
        val inserted = wanted.size - tailUuids.size
        if (wanted.drop(inserted) == tailUuids) {
            return QueueEdit.PlayNext(wanted.take(inserted))
        }
    }
    var matched = 0
    val removed = mutableListOf<String>()
    tail.forEach { item ->
        if (wanted.getOrNull(matched) == item.episodeUuid) {
            matched++
        } else {
            removed += item.queueItemId
        }
    }
    val added = wanted.drop(matched)
    val tailSet = tailUuids.toSet()
    if (added.none { it in tailSet }) {
        return if (added.isEmpty()) QueueEdit.Remove(removed) else QueueEdit.RemoveAndAppend(removed, added)
    }
    return QueueEdit.ReplaceUpcoming(wanted)
}
