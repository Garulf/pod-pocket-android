package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePlanTest {
    private val mirror = listOf(item("a"), item("b"), item("c"))

    @Test
    fun `does nothing when the order already matches`() {
        assertEquals(edit(QueueEdit.None), plan(mirror, listOf("a", "b", "c")))
        assertEquals(edit(QueueEdit.None), plan(listOf(item("a")), listOf("a")))
    }

    @Test
    fun `restarts when the current episode changes`() {
        assertEquals(edit(QueueEdit.Restart), plan(mirror, listOf("b", "c")))
    }

    @Test
    fun `restarts when Up Next is empty`() {
        assertEquals(edit(QueueEdit.Restart), plan(mirror, emptyList()))
    }

    @Test
    fun `restarts when nothing is mirrored`() {
        assertEquals(edit(QueueEdit.Restart), plan(emptyList(), listOf("a")))
    }

    @Test
    fun `restarts when the current episode is removed while others remain`() {
        assertEquals(edit(QueueEdit.Restart), plan(mirror, listOf("c")))
        assertEquals(edit(QueueEdit.Restart), plan(mirror, listOf("b", "c"), buffered = 1))
    }

    @Test
    fun `appends episodes added at the end`() {
        assertEquals(edit(QueueEdit.Append(listOf("d", "e"))), plan(mirror, listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun `appends the 25th episode to a mirror of 24`() {
        val full = (1..24).map { item("m$it") }
        val upNext = (1..25).map { "m$it" }
        assertEquals(edit(QueueEdit.Append(listOf("m25"))), plan(full, upNext))
    }

    @Test
    fun `appends rather than plays next when only the current episode is mirrored`() {
        assertEquals(edit(QueueEdit.Append(listOf("d"))), plan(listOf(item("a")), listOf("a", "d")))
    }

    @Test
    fun `plays next episodes added after the current one`() {
        assertEquals(edit(QueueEdit.PlayNext(listOf("d"))), plan(mirror, listOf("a", "d", "b", "c")))
        assertEquals(edit(QueueEdit.PlayNext(listOf("d", "e"))), plan(mirror, listOf("a", "d", "e", "b", "c")))
    }

    @Test
    fun `plays next after the buffered item`() {
        assertEquals(edit(QueueEdit.PlayNext(listOf("d"))), plan(mirror, listOf("a", "b", "d", "c"), buffered = 1))
    }

    @Test
    fun `deletes removed episodes by their queue item`() {
        assertEquals(edit(QueueEdit.Remove(listOf("qb"))), plan(mirror, listOf("a", "c")))
        assertEquals(edit(QueueEdit.Remove(listOf("qb", "qc"))), plan(mirror, listOf("a")))
    }

    @Test
    fun `deletes non adjacent removals in mirror order and never the current item`() {
        val longer = listOf(item("a"), item("b"), item("c"), item("d"), item("e"))
        assertEquals(edit(QueueEdit.Remove(listOf("qb", "qd"))), plan(longer, listOf("a", "c", "e")))
    }

    @Test
    fun `never deletes a buffered item and skips it on arrival instead`() {
        assertEquals(QueuePlan(QueueEdit.None, listOf("qb")), plan(mirror, listOf("a", "c"), buffered = 1))
        assertEquals(QueuePlan(QueueEdit.None, listOf("qb", "qc")), plan(mirror, listOf("a"), buffered = 2))
        assertEquals(QueuePlan(QueueEdit.None, listOf("qb")), plan(mirror, listOf("a", "c"), buffered = 2))
    }

    @Test
    fun `deletes the unbuffered items behind a kept buffered item`() {
        assertEquals(edit(QueueEdit.Remove(listOf("qc"))), plan(mirror, listOf("a", "b"), buffered = 1))
    }

    @Test
    fun `treats a buffered count beyond the mirror as the whole mirror`() {
        assertEquals(edit(QueueEdit.None), plan(mirror, listOf("a", "b", "c"), buffered = 5))
    }

    @Test
    fun `replaces the upcoming items after a move or a mixed edit`() {
        assertEquals(edit(QueueEdit.ReplaceUpcoming(listOf("c", "b"))), plan(mirror, listOf("a", "c", "b")))
        assertEquals(edit(QueueEdit.ReplaceUpcoming(listOf("d", "c"))), plan(mirror, listOf("a", "d", "c")))
        assertEquals(edit(QueueEdit.ReplaceUpcoming(listOf("b", "d", "c"))), plan(mirror, listOf("a", "b", "d", "c")))
    }

    @Test
    fun `replaces the upcoming items for a reordered Up Next with a new episode`() {
        assertEquals(edit(QueueEdit.ReplaceUpcoming(listOf("d", "c", "b"))), plan(mirror, listOf("a", "d", "c", "b")))
    }

    @Test
    fun `replaces only the items after the buffered ones`() {
        val four = listOf(item("a"), item("b"), item("c"), item("d"))
        assertEquals(
            edit(QueueEdit.ReplaceUpcoming(listOf("d", "c"))),
            plan(four, listOf("a", "b", "d", "c"), buffered = 1),
        )
    }

    @Test
    fun `moving the buffered item skips it and appends a fresh copy`() {
        assertEquals(edit(QueueEdit.ReplaceUpcoming(listOf("c", "b"))), plan(mirror, listOf("a", "c", "b"), buffered = 0))
        assertEquals(QueuePlan(QueueEdit.Append(listOf("b")), listOf("qb")), plan(mirror, listOf("a", "c", "b"), buffered = 1))
        assertEquals(QueuePlan(QueueEdit.Append(listOf("b")), listOf("qb")), plan(mirror, listOf("a", "c", "b"), buffered = 2))
    }

    @Test
    fun `moving an episode ahead of the buffered item plays it next after the buffered item`() {
        assertEquals(
            QueuePlan(QueueEdit.PlayNext(listOf("d", "b")), listOf("qb")),
            plan(mirror, listOf("a", "d", "b", "c"), buffered = 1),
        )
    }

    @Test
    fun `removes then appends when a removal pulls in an episode beyond the cap`() {
        val full = (1..25).map { item("m$it") }
        val upNext = listOf("m1") + (3..26).map { "m$it" }
        assertEquals(edit(QueueEdit.RemoveAndAppend(listOf("qm2"), listOf("m26"))), plan(full, upNext))
    }

    @Test
    fun `appends only when the removed episode past the cap is buffered`() {
        val full = (1..25).map { item("m$it") }
        val upNext = listOf("m1") + (3..26).map { "m$it" }
        assertEquals(QueuePlan(QueueEdit.Append(listOf("m26")), listOf("qm2")), plan(full, upNext, buffered = 1))
    }

    @Test
    fun `removes then appends for a removal with an addition at equal and smaller sizes`() {
        assertEquals(edit(QueueEdit.RemoveAndAppend(listOf("qb"), listOf("d"))), plan(mirror, listOf("a", "c", "d")))
        assertEquals(edit(QueueEdit.RemoveAndAppend(listOf("qc"), listOf("d"))), plan(mirror, listOf("a", "b", "d")))
        assertEquals(edit(QueueEdit.RemoveAndAppend(listOf("qb", "qc"), listOf("d"))), plan(mirror, listOf("a", "d")))
    }

    @Test
    fun `deletes the later copy when an episode is mirrored twice`() {
        val twice = listOf(MirroredItem("a", "q1", "uri:a"), MirroredItem("a", "q2", "uri:a"))
        assertEquals(edit(QueueEdit.Remove(listOf("q2"))), plan(twice, listOf("a")))
    }

    @Test
    fun `keeps the first copy and deletes the rest of a duplicated episode`() {
        val dupes = listOf(item("a"), MirroredItem("b", "q1", "uri:b"), MirroredItem("b", "q2", "uri:b"))
        assertEquals(edit(QueueEdit.Remove(listOf("q2"))), plan(dupes, listOf("a", "b")))
        assertEquals(edit(QueueEdit.None), plan(dupes, listOf("a", "b", "b")))
    }

    @Test
    fun `never replaces the upcoming items with an empty list`() {
        val plans = listOf(
            plan(mirror, listOf("a")),
            plan(listOf(item("a"), item("a")), listOf("a")),
            plan(mirror, listOf("a"), buffered = 1),
        )
        plans.forEach { assertEquals(false, it.edit is QueueEdit.ReplaceUpcoming && it.edit.episodeUuids.isEmpty()) }
    }

    @Test
    fun `does not alias the inputs in the plan`() {
        val items = mutableListOf(item("a"), item("b"), item("c"))
        val upNext = mutableListOf("a", "b", "c", "d")
        val appended = plan(items, upNext)

        val removeItems = mutableListOf(item("a"), item("b"), item("c"))
        val removeUpNext = mutableListOf("a", "c")
        val removed = plan(removeItems, removeUpNext)

        val replaceUpNext = mutableListOf("a", "c", "b")
        val replaced = plan(items, replaceUpNext)

        upNext.clear()
        items.clear()
        removeUpNext.clear()
        removeItems.clear()
        replaceUpNext.clear()

        assertEquals(edit(QueueEdit.Append(listOf("d"))), appended)
        assertEquals(edit(QueueEdit.Remove(listOf("qb"))), removed)
        assertEquals(edit(QueueEdit.ReplaceUpcoming(listOf("c", "b"))), replaced)
    }

    private fun plan(mirror: List<MirroredItem>, upNext: List<String>, buffered: Int = 0) = planQueueEdit(mirror, upNext, buffered)

    private fun edit(edit: QueueEdit) = QueuePlan(edit)

    private fun item(uuid: String) = MirroredItem(uuid, "q$uuid", "uri:$uuid")
}
