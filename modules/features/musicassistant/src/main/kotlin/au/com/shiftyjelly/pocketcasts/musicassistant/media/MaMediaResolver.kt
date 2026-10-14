package au.com.shiftyjelly.pocketcasts.musicassistant.media

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaCommandException
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaRemote
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaMediaItem
import com.squareup.moshi.JsonDataException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface MaMedia {
    val episodeUuid: String
    val uri: String

    data class ProviderEpisode(override val episodeUuid: String, override val uri: String) : MaMedia

    data class PlainUrl(override val episodeUuid: String, override val uri: String) : MaMedia
}

class MaMediaResolver(
    private val remote: MaRemote,
    private val sources: EpisodeSourceLookup,
    private val gpodderInstanceId: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class CachedEpisodes(val episodes: List<MaMediaItem>, val fetchedAtMs: Long)

    private val episodesByFeed = ConcurrentHashMap<String, CachedEpisodes>()

    suspend fun resolve(episode: PodcastEpisode): MaMedia? {
        val plain = plainUrl(episode)
        val instanceId = gpodderInstanceId() ?: return plain
        val source = sources.find(listOf(episode.uuid))[episode.uuid] ?: return plain
        val candidates = podcastEpisodes(source.feedUrl, instanceId) ?: return plain
        val match = candidates.firstOrNull { item -> item.providerMappings.any { it.url == source.enclosureUrl || it.url == episode.downloadUrl } }
            ?: guidItemIds(source).firstNotNullOfOrNull { itemId -> candidates.firstOrNull { it.itemId == itemId } }
            ?: return plain
        return MaMedia.ProviderEpisode(episode.uuid, match.uri ?: "$instanceId://podcast_episode/${match.itemId}")
    }

    fun plainUrl(episode: PodcastEpisode): MaMedia? {
        return episode.downloadUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { MaMedia.PlainUrl(episode.uuid, "$BUILTIN_TRACK$it") }
    }

    private suspend fun podcastEpisodes(feedUrl: String, instanceId: String): List<MaMediaItem>? {
        episodesByFeed[feedUrl]?.takeIf { now() - it.fetchedAtMs < CACHE_MS }?.let { return it.episodes }
        val episodes = try {
            MaJson.decodeList<MaMediaItem>(
                remote.call(
                    "music/podcasts/podcast_episodes",
                    mapOf("item_id" to feedUrl, "provider_instance_id_or_domain" to instanceId),
                    timeoutMs = LISTING_TIMEOUT_MS,
                ),
            )
        } catch (e: MaCommandException) {
            return null
        } catch (e: IOException) {
            return null
        } catch (e: JsonDataException) {
            return null
        }
        episodesByFeed[feedUrl] = CachedEpisodes(episodes, now())
        return episodes
    }

    private fun guidItemIds(source: EpisodeSource): List<String> {
        val guid = source.guid?.trim()?.takeIf { it.isNotEmpty() && ' ' !in it } ?: return emptyList()
        val resolved = source.feedUrl.toHttpUrlOrNull()?.resolve(guid)?.toString()
        return listOfNotNull(guid, resolved).distinct().map { "${source.feedUrl} $it" }
    }

    private companion object {
        const val CACHE_MS = 10 * 60 * 1000L
        const val LISTING_TIMEOUT_MS = 30_000L

        // A bare URL could come back as a sound effect when the file has no music tags; asking for a track never does.
        const val BUILTIN_TRACK = "builtin://track/"
    }
}
