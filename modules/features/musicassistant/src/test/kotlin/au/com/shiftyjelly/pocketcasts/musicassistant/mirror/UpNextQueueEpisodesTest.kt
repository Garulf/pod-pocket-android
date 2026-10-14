package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.UserEpisode
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import java.util.Date
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class UpNextQueueEpisodesTest {
    private val a = PodcastEpisode(uuid = "a", publishedDate = Date())
    private val b = PodcastEpisode(uuid = "b", publishedDate = Date())
    private val file = UserEpisode(uuid = "file", publishedDate = Date())

    @Test
    fun `lists the current episode first and skips files`() = runTest {
        val queue = mock<UpNextQueue> {
            on { changesFlow } doReturn flowOf(UpNextQueue.State.Loaded(a, null, listOf(file, b)))
            on { allEpisodes } doReturn listOf(a, file, b)
        }

        val episodes = UpNextQueueEpisodes(queue)

        assertEquals(listOf("a", "b"), episodes.changes.first().map { it.uuid })
        assertEquals(listOf("a", "b"), episodes.current().map { it.uuid })
    }

    @Test
    fun `is empty when Up Next is`() = runTest {
        val queue = mock<UpNextQueue> { on { changesFlow } doReturn flowOf(UpNextQueue.State.Empty) }

        assertEquals(emptyList<PodcastEpisode>(), UpNextQueueEpisodes(queue).changes.first())
    }
}
