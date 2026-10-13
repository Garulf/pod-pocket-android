package au.com.shiftyjelly.pocketcasts.selfhosted

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

fun interface ServerConnectionChecker {
    suspend fun check(server: HttpUrl): ConnectionResult
}

sealed interface ConnectionResult {
    data object Reachable : ConnectionResult

    data class Unreachable(val reason: String) : ConnectionResult
}

class OkHttpServerConnectionChecker(
    private val client: OkHttpClient,
) : ServerConnectionChecker {
    override suspend fun check(server: HttpUrl): ConnectionResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(server.newBuilder().addPathSegment("health").build())
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    !response.isSuccessful -> ConnectionResult.Unreachable("HTTP ${response.code}")
                    HEALTHY_BODY.containsMatchIn(response.body.string()) -> ConnectionResult.Reachable
                    else -> ConnectionResult.Unreachable("not a Pod Pocket server")
                }
            }
        } catch (e: IOException) {
            ConnectionResult.Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        val HEALTHY_BODY = Regex(""""status"\s*:\s*"ok"""")
    }
}
