package au.com.shiftyjelly.pocketcasts.musicassistant.media

import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@JsonClass(generateAdapter = true)
data class EpisodeSource(
    val uuid: String,
    @Json(name = "feed_url") val feedUrl: String,
    val guid: String? = null,
    @Json(name = "enclosure_url") val enclosureUrl: String,
)

@JsonClass(generateAdapter = true)
internal data class EpisodeSourcesRequest(val uuids: List<String>)

@JsonClass(generateAdapter = true)
internal data class EpisodeSourcesResponse(val episodes: List<EpisodeSource>)

fun interface EpisodeSourceLookup {
    suspend fun find(episodeUuids: Collection<String>): Map<String, EpisodeSource>
}

class ServerEpisodeSourceLookup(
    private val client: OkHttpClient,
    private val apiBaseUrl: HttpUrl,
) : EpisodeSourceLookup {
    private val requestAdapter = MaJson.moshi.adapter(EpisodeSourcesRequest::class.java)
    private val responseAdapter = MaJson.moshi.adapter(EpisodeSourcesResponse::class.java)

    override suspend fun find(episodeUuids: Collection<String>): Map<String, EpisodeSource> = withContext(Dispatchers.IO) {
        episodeUuids.distinct().chunked(MAX_PER_REQUEST).flatMap { fetch(it) }.associateBy { it.uuid }
    }

    private fun fetch(episodeUuids: List<String>): List<EpisodeSource> {
        val request = Request.Builder()
            .url(apiBaseUrl.newBuilder().addPathSegments("podpocket/episode_sources").build())
            .post(requestAdapter.toJson(EpisodeSourcesRequest(episodeUuids)).toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) responseAdapter.fromJson(response.body.source())?.episodes.orEmpty() else emptyList()
            }
        } catch (e: IOException) {
            emptyList()
        } catch (e: JsonDataException) {
            emptyList()
        }
    }

    private companion object {
        const val MAX_PER_REQUEST = 500
        val JSON = "application/json".toMediaType()
    }
}
