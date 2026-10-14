package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import kotlinx.coroutines.flow.Flow

interface UpNextEpisodes {
    val changes: Flow<List<PodcastEpisode>>

    fun current(): List<PodcastEpisode>
}

/**
 * Marks episodes the speaker has played as played and takes them out of Up Next.
 *
 * The caller holds the speaker's player lock while awaiting [finish], so an implementation must never
 * call a Player method, never await a PlaybackManager call that reaches the player, and never use
 * withTimeout. Up Next is changed with direct suspend calls that finish before [finish] returns;
 * fire-and-forget launches are fine.
 */
fun interface EpisodeFinisher {
    suspend fun finish(episodeUuids: List<String>)
}
