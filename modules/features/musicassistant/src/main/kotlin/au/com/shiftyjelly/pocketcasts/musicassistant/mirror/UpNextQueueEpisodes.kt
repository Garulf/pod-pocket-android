package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.repositories.playback.SleepTimerState
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

class UpNextQueueEpisodes(
    private val upNextQueue: UpNextQueue,
    private val sleepTimer: StateFlow<SleepTimerState>,
    private val upNextShuffle: StateFlow<Boolean>,
) : UpNextEpisodes {
    override val changes: Flow<List<BaseEpisode>> = combine(upNextQueue.changesFlow, sleepTimer, upNextShuffle) { state, timer, shuffle ->
        val episodes = when (state) {
            is UpNextQueue.State.Loaded -> listOf(state.episode) + state.queue
            UpNextQueue.State.Empty -> emptyList()
        }
        inPlayOrder(episodes, timer, shuffle)
    }

    override fun current(): List<BaseEpisode> = inPlayOrder(upNextQueue.allEpisodes, sleepTimer.value, upNextShuffle.value)

    // With shuffle on the app picks the next episode at random, and the sleep timer stops playback after this one.
    private fun inPlayOrder(episodes: List<BaseEpisode>, timer: SleepTimerState, shuffle: Boolean): List<BaseEpisode> {
        val stopsAfterCurrent = timer.isSleepTimerRunning && timer.numberOfEpisodesLeft == 1
        return if (shuffle || stopsAfterCurrent) episodes.take(1) else episodes
    }
}
