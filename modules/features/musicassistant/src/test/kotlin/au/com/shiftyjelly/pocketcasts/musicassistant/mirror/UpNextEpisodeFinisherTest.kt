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
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpNextEpisodeFinisherTest {
    private val episode = PodcastEpisode(uuid = "b", publishedDate = Date())
    private val episodeManager = mock<EpisodeManager> {
        on { findByUuids(listOf("b")) } doReturn listOf(episode)
    }
    private val podcastManager = mock<PodcastManager>()
    private val upNextQueue = mock<UpNextQueue>()
    private val playbackManager = mock<PlaybackManager>()

    @Test
    fun `marks the episode played, takes it out of Up Next before archiving it and leaves the player alone`() = runTest {
        UpNextEpisodeFinisher(episodeManager, podcastManager, upNextQueue, { playbackManager }).finish(listOf("b"))

        inOrder(episodeManager, upNextQueue) {
            verify(episodeManager).updatePlayingStatusBlocking(episode, EpisodePlayingStatus.COMPLETED)
            verify(upNextQueue).removeEpisode(episode, false)
            verify(episodeManager).archivePlayedEpisode(episode, playbackManager, podcastManager, true)
        }
        verifyNoInteractions(playbackManager)
    }
}
