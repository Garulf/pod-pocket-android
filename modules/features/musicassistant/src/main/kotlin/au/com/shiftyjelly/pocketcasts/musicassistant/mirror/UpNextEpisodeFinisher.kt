package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import au.com.shiftyjelly.pocketcasts.models.type.EpisodePlayingStatus
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

class UpNextEpisodeFinisher(
    private val episodeManager: EpisodeManager,
    private val podcastManager: PodcastManager,
    private val upNextQueue: UpNextQueue,
    private val playbackManager: Provider<PlaybackManager>,
) : EpisodeFinisher {
    override suspend fun finish(episodeUuids: List<String>) {
        withContext(Dispatchers.IO) {
            for (episode in episodeManager.findByUuids(episodeUuids)) {
                ensureActive()
                try {
                    episodeManager.updatePlayingStatusBlocking(episode, EpisodePlayingStatus.COMPLETED)
                    upNextQueue.removeEpisode(episode)
                    episodeManager.archivePlayedEpisode(episode, playbackManager.get(), podcastManager, sync = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("Could not finish an episode the speaker played: ${e.javaClass.simpleName}")
                }
            }
        }
    }
}
