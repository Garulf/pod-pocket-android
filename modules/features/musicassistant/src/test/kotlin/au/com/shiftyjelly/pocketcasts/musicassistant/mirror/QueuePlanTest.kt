package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePlanTest {
    private val mirror = listOf(item("a"), item("b"), item("c"))

    @Test
    fun `does nothing when the order already matches`() {
        assertEquals(QueueEdit.None, planQueueEdit(mirror, listOf("a", "b", "c")))
    }

    @Test
    fun `restarts when the current episode changes`() {
        assertEquals(QueueEdit.Restart, planQueueEdit(mirror, listOf("b", "c")))
        assertEquals(QueueEdit.Restart, planQueueEdit(mirror, emptyList()))
        assertEquals(QueueEdit.Restart, planQueueEdit(emptyList(), listOf("a")))
    }

    @Test
    fun `appends episodes added at the end`() {
        assertEquals(QueueEdit.Append(listOf("d", "e")), planQueueEdit(mirror, listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun `plays next episodes added after the current one`() {
        assertEquals(QueueEdit.PlayNext(listOf("d")), planQueueEdit(mirror, listOf("a", "d", "b", "c")))
        assertEquals(QueueEdit.PlayNext(listOf("d", "e")), planQueueEdit(mirror, listOf("a", "d", "e", "b", "c")))
    }

    @Test
    fun `deletes removed episodes by their queue item`() {
        assertEquals(QueueEdit.Remove(listOf("qb")), planQueueEdit(mirror, listOf("a", "c")))
        assertEquals(QueueEdit.Remove(listOf("qb", "qc")), planQueueEdit(mirror, listOf("a")))
    }

    @Test
    fun `replaces the upcoming items after a move or a mixed edit`() {
        assertEquals(QueueEdit.ReplaceUpcoming(listOf("c", "b")), planQueueEdit(mirror, listOf("a", "c", "b")))
        assertEquals(QueueEdit.ReplaceUpcoming(listOf("d", "c")), planQueueEdit(mirror, listOf("a", "d", "c")))
        assertEquals(QueueEdit.ReplaceUpcoming(listOf("b", "d", "c")), planQueueEdit(mirror, listOf("a", "b", "d", "c")))
    }

    private fun item(uuid: String) = MirroredItem(uuid, "q$uuid", "uri:$uuid")
}
