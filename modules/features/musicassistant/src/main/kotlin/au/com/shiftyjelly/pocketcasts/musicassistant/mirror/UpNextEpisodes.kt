package au.com.shiftyjelly.pocketcasts.musicassistant.mirror

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import kotlinx.coroutines.flow.Flow

/** The episodes the speaker may play in order, current first. Episodes Music Assistant cannot play, such as files, stay in place. */
interface UpNextEpisodes {
    val changes: Flow<List<BaseEpisode>>

    fun current(): List<BaseEpisode>
}

/**
 * Marks episodes the speaker has played as played, takes them out of Up Next and archives them
 * as the user's settings ask.
 *
 * The caller may hold the speaker's player lock while awaiting [finish], so an implementation must
 * never call a Player method, never await a PlaybackManager call that reaches the player, and never
 * use withTimeout. Up Next is changed with direct suspend calls that finish before [finish] returns,
 * and before archiving is started, because archiving can reach PlaybackManager.removeEpisode.
 * Fire-and-forget launches are fine.
 *
 * Callers bound [finish] with a 10 second timeout. The bound is cooperative: it takes effect when the
 * implementation checks for cancellation between episodes, and does not interrupt a blocking database
 * call that is already running.
 */
fun interface EpisodeFinisher {
    suspend fun finish(episodeUuids: List<String>)
}
