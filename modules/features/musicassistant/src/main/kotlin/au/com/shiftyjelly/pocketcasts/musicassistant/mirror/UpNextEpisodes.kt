package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import kotlinx.coroutines.flow.Flow

interface UpNextEpisodes {
    val changes: Flow<List<PodcastEpisode>>

    fun current(): List<PodcastEpisode>
}

fun interface EpisodeFinisher {
    suspend fun finish(episodeUuids: List<String>)
}
