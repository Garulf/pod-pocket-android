package au.com.shiftyjelly.pocketcasts.musicassistant.media

import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber

@JsonClass(generateAdapter = true)
data class EpisodeSource(
    val uuid: String,
    @Json(name = "feed_url") val feedUrl: String,
    val guid: String? = null,
    @Json(name = "enclosure_url") val enclosureUrl: String,
)

@JsonClass(generateAdapter = true)
internal data class EpisodeSourcesRequest(val uuids: List<String>)

fun interface EpisodeSourceLookup {
    suspend fun find(episodeUuids: Collection<String>): Map<String, EpisodeSource>
}

class ServerEpisodeSourceLookup(
    private val client: Call.Factory,
    private val apiBaseUrl: HttpUrl,
) : EpisodeSourceLookup {
    private val requestAdapter = MaJson.moshi.adapter(EpisodeSourcesRequest::class.java)
    private val bodyAdapter = MaJson.moshi.adapter(Any::class.java)

    override suspend fun find(episodeUuids: Collection<String>): Map<String, EpisodeSource> {
        return episodeUuids.distinct().chunked(MAX_PER_REQUEST).flatMap { fetch(it) }.associateBy { it.uuid }
    }

    private suspend fun fetch(episodeUuids: List<String>): List<EpisodeSource> = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(apiBaseUrl.newBuilder().addPathSegments("podpocket/episode_sources").build())
            .post(requestAdapter.toJson(EpisodeSourcesRequest(episodeUuids)).toRequestBody(JSON))
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Timber.w("Episode source lookup failed: ${e.javaClass.simpleName}")
                    continuation.resume(emptyList())
                }

                override fun onResponse(call: Call, response: Response) {
                    val sources = try {
                        response.use(::readSources)
                    } catch (e: RuntimeException) {
                        Timber.w("Episode source lookup failed: ${e.javaClass.simpleName}")
                        emptyList()
                    }
                    continuation.resume(sources)
                }
            },
        )
    }

    private fun readSources(response: Response): List<EpisodeSource> {
        if (!response.isSuccessful) {
            Timber.w("Episode source lookup was refused with status ${response.code}")
            return emptyList()
        }
        return try {
            val body = bodyAdapter.fromJson(response.body.source()) as? Map<*, *>
            MaJson.decodeList(body?.get("episodes"), EpisodeSource::class.java)
        } catch (e: IOException) {
            Timber.w("Episode source lookup answered something unreadable")
            emptyList()
        } catch (e: JsonDataException) {
            Timber.w("Episode source lookup answered something unreadable")
            emptyList()
        }
    }

    private companion object {
        const val MAX_PER_REQUEST = 500
        val JSON = "application/json".toMediaType()
    }
}
