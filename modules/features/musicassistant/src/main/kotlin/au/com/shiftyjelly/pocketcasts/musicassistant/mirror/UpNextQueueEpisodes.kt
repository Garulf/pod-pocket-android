package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UpNextQueueEpisodes(private val upNextQueue: UpNextQueue) : UpNextEpisodes {
    override val changes: Flow<List<PodcastEpisode>> = upNextQueue.changesFlow.map { state ->
        when (state) {
            is UpNextQueue.State.Loaded -> (listOf(state.episode) + state.queue).filterIsInstance<PodcastEpisode>()
            UpNextQueue.State.Empty -> emptyList()
        }
    }

    override fun current(): List<PodcastEpisode> = upNextQueue.allEpisodes.filterIsInstance<PodcastEpisode>()
}
