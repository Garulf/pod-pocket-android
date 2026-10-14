package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.UserEpisode
import au.com.shiftyjelly.pocketcasts.repositories.playback.SleepTimerState
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class UpNextQueueEpisodesTest {
    private val a = PodcastEpisode(uuid = "a", publishedDate = Date())
    private val b = PodcastEpisode(uuid = "b", publishedDate = Date())
    private val file = UserEpisode(uuid = "file", publishedDate = Date())
    private val sleepTimer = MutableStateFlow(SleepTimerState())
    private val shuffle = MutableStateFlow(false)
    private val queue = mock<UpNextQueue> {
        on { changesFlow } doReturn flowOf(UpNextQueue.State.Loaded(a, null, listOf(file, b)))
        on { allEpisodes } doReturn listOf(a, file, b)
    }
    private val episodes = UpNextQueueEpisodes(queue, sleepTimer, shuffle)

    @Test
    fun `lists the current episode first and keeps files in place`() = runTest {
        assertEquals(listOf("a", "file", "b"), episodes.changes.first().map { it.uuid })
        assertEquals(listOf("a", "file", "b"), episodes.current().map { it.uuid })
    }

    @Test
    fun `is empty when Up Next is`() = runTest {
        val empty = mock<UpNextQueue> { on { changesFlow } doReturn flowOf(UpNextQueue.State.Empty) }

        assertEquals(emptyList<BaseEpisode>(), UpNextQueueEpisodes(empty, sleepTimer, shuffle).changes.first())
    }

    @Test
    fun `lists only the current episode while the sleep timer stops after it`() = runTest {
        sleepTimer.value = SleepTimerState(isSleepTimerRunning = true, numberOfEpisodesLeft = 1)

        assertEquals(listOf("a"), episodes.changes.first().map { it.uuid })
        assertEquals(listOf("a"), episodes.current().map { it.uuid })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `lists Up Next again as the sleep timer changes`() = runTest {
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch { episodes.changes.collect { list -> seen += list.map { it.uuid } } }
        runCurrent()

        sleepTimer.value = SleepTimerState(isSleepTimerRunning = true, numberOfEpisodesLeft = 2)
        runCurrent()
        sleepTimer.value = SleepTimerState(isSleepTimerRunning = true, numberOfEpisodesLeft = 1)
        runCurrent()
        sleepTimer.value = SleepTimerState()
        runCurrent()

        assertEquals(listOf(listOf("a", "file", "b"), listOf("a", "file", "b"), listOf("a"), listOf("a", "file", "b")), seen)
    }

    @Test
    fun `lists only the current episode while Up Next is shuffled`() = runTest {
        shuffle.value = true

        assertEquals(listOf("a"), episodes.changes.first().map { it.uuid })
        assertEquals(listOf("a"), episodes.current().map { it.uuid })
    }
}
