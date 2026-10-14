package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.type.EpisodePlayingStatus
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import java.util.Date
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpNextEpisodeFinisherTest {
    private val a = PodcastEpisode(uuid = "a", publishedDate = Date())
    private val b = PodcastEpisode(uuid = "b", publishedDate = Date())
    private val c = PodcastEpisode(uuid = "c", publishedDate = Date())
    private val episodeManager = mock<EpisodeManager>()
    private val podcastManager = mock<PodcastManager>()
    private val upNextQueue = mock<UpNextQueue>()
    private val playbackManager = mock<PlaybackManager>()
    private val finisher = UpNextEpisodeFinisher(episodeManager, podcastManager, upNextQueue, { playbackManager })

    @Test
    fun `marks the episode played, takes it out of Up Next before archiving it and leaves the player alone`() = runTest {
        episodeManager.finds(listOf("b"), b)

        finisher.finish(listOf("b"))

        inOrder(episodeManager, upNextQueue) {
            verify(episodeManager).updatePlayingStatusBlocking(b, EpisodePlayingStatus.COMPLETED)
            verify(upNextQueue).removeEpisode(b, false)
            verify(episodeManager).archivePlayedEpisode(b, playbackManager, podcastManager, true)
        }
        verifyNoInteractions(playbackManager)
    }

    @Test
    fun `finishes several episodes one after the other`() = runTest {
        episodeManager.finds(listOf("a", "b", "c"), a, b, c)

        finisher.finish(listOf("a", "b", "c"))

        inOrder(episodeManager, upNextQueue) {
            for (episode in listOf(a, b, c)) {
                verify(episodeManager).updatePlayingStatusBlocking(episode, EpisodePlayingStatus.COMPLETED)
                verify(upNextQueue).removeEpisode(episode, false)
                verify(episodeManager).archivePlayedEpisode(episode, playbackManager, podcastManager, true)
            }
        }
    }

    @Test
    fun `skips an episode that is not in the database and finishes the others`() = runTest {
        episodeManager.finds(listOf("a", "gone", "c"), a, c)

        finisher.finish(listOf("a", "gone", "c"))

        inOrder(episodeManager, upNextQueue) {
            verify(upNextQueue).removeEpisode(a, false)
            verify(upNextQueue).removeEpisode(c, false)
        }
        verifyNoMoreInteractions(upNextQueue)
    }

    @Test
    fun `finishes the other episodes when one fails`() = runTest {
        episodeManager.finds(listOf("a", "b", "c"), a, b, c)
        doThrow(IllegalStateException("database is locked")).whenever(episodeManager).updatePlayingStatusBlocking(b, EpisodePlayingStatus.COMPLETED)

        finisher.finish(listOf("a", "b", "c"))

        inOrder(upNextQueue) {
            verify(upNextQueue).removeEpisode(a, false)
            verify(upNextQueue).removeEpisode(c, false)
        }
        verify(episodeManager).archivePlayedEpisode(c, playbackManager, podcastManager, true)
    }

    private suspend fun EpisodeManager.finds(uuids: List<String>, vararg episodes: PodcastEpisode) {
        whenever(findByUuids(uuids)).doReturn(episodes.toList())
    }
}
