package au.com.shiftyjelly.pocketcasts.musicassistant.media

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaCommandException
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaRemote
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaMediaItem
import com.squareup.moshi.JsonDataException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

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
    private data class FeedKey(val instanceId: String, val feedUrl: String)

    private class FeedLock {
        val mutex = Mutex()
        var users = 0
    }

    private class CachedEpisodes(val episodes: List<MaMediaItem>, val fetchedAtMs: Long)

    private val episodesByFeed = ConcurrentHashMap<FeedKey, CachedEpisodes>()
    private val feedLocks = HashMap<FeedKey, FeedLock>()

    suspend fun resolve(episode: PodcastEpisode): MaMedia? = resolveAll(listOf(episode)).firstOrNull()

    suspend fun resolveAll(episodes: List<PodcastEpisode>): List<MaMedia> {
        val instanceId = gpodderInstanceId()
        if (instanceId == null || !remote.isConnected || episodes.isEmpty()) {
            return episodes.mapNotNull(::plainUrl)
        }
        val found = sources.find(episodes.map { it.uuid })
        val candidatesByFeed = listFeeds(episodes.mapNotNull { found[it.uuid] }.map { FeedKey(instanceId, it.feedUrl) }.distinct())
        return episodes.mapNotNull { episode ->
            val source = found[episode.uuid]
            val candidates = source?.let { candidatesByFeed[FeedKey(instanceId, it.feedUrl)] }
            val provided = if (source != null && candidates != null) providerEpisode(episode, source, candidates, instanceId) else null
            provided ?: plainUrl(episode)
        }
    }

    private suspend fun listFeeds(feeds: List<FeedKey>): Map<FeedKey, List<MaMediaItem>?> = supervisorScope {
        feeds.associateWith { key -> async { podcastEpisodes(key) } }.mapValues { (key, listing) -> listedOrNull(key, listing) }
    }

    private suspend fun listedOrNull(key: FeedKey, listing: Deferred<List<MaMediaItem>?>): List<MaMediaItem>? {
        return try {
            listing.await()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.w(e, "Could not list the episodes of a feed on ${key.instanceId}")
            null
        }
    }

    private fun providerEpisode(episode: PodcastEpisode, source: EpisodeSource, candidates: List<MaMediaItem>, instanceId: String): MaMedia? {
        val wanted = listOfNotNull(source.enclosureUrl, episode.downloadUrl).map(::normalised)
        val match = candidates.firstOrNull { item -> item.providerMappings.any { mapping -> mapping.url?.let(::normalised) in wanted } }
            ?: guidItemIds(source).firstNotNullOfOrNull { itemId -> candidates.firstOrNull { it.itemId == itemId } }
            ?: return null
        return MaMedia.ProviderEpisode(episode.uuid, match.uri ?: "$instanceId://podcast_episode/${match.itemId}")
    }

    fun plainUrl(episode: PodcastEpisode): MaMedia? {
        return episode.downloadUrl?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?.let { MaMedia.PlainUrl(episode.uuid, "$BUILTIN_TRACK$it") }
    }

    private suspend fun podcastEpisodes(key: FeedKey): List<MaMediaItem>? {
        val lock = synchronized(feedLocks) { feedLocks.getOrPut(key) { FeedLock() }.also { it.users++ } }
        try {
            return lock.mutex.withLock {
                cachedEpisodes(key) ?: listEpisodes(key)?.also { episodesByFeed[key] = CachedEpisodes(it, now()) }
            }
        } finally {
            synchronized(feedLocks) {
                lock.users--
                if (lock.users == 0) {
                    feedLocks.remove(key)
                }
            }
        }
    }

    private fun cachedEpisodes(key: FeedKey): List<MaMediaItem>? {
        return episodesByFeed[key]?.takeIf { now() - it.fetchedAtMs < CACHE_MS }?.episodes
    }

    private suspend fun listEpisodes(key: FeedKey): List<MaMediaItem>? {
        return try {
            MaJson.decodeList<MaMediaItem>(
                remote.call(
                    "music/podcasts/podcast_episodes",
                    mapOf("item_id" to key.feedUrl, "provider_instance_id_or_domain" to key.instanceId),
                    timeoutMs = LISTING_TIMEOUT_MS,
                ),
            )
        } catch (e: MaCommandException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: JsonDataException) {
            null
        }
    }

    private fun normalised(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url

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
