package au.com.shiftyjelly.pocketcasts.selfhosted

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

fun interface ServerConnectionChecker {
    suspend fun check(server: HttpUrl): ConnectionResult
}

sealed interface ConnectionResult {
    data object Reachable : ConnectionResult

    data class Unreachable(val reason: String) : ConnectionResult
}

const val NOT_POD_POCKET_REASON = "not a Pod Pocket server"

class OkHttpServerConnectionChecker(
    client: OkHttpClient,
) : ServerConnectionChecker {
    private val client = client.newBuilder()
        .dns(SelfHostedBlockingDns())
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(SelfHostedBlockNetworkInterceptor())
        .build()

    override suspend fun check(server: HttpUrl): ConnectionResult = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(server.newBuilder().addPathSegment("health").build())
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(ConnectionResult.Unreachable(e.message ?: e.javaClass.simpleName))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response.use(::evaluate))
                }
            },
        )
    }

    private fun evaluate(response: Response): ConnectionResult = when {
        response.isRedirect -> NOT_POD_POCKET
        !response.isSuccessful -> ConnectionResult.Unreachable("HTTP ${response.code}")
        isHealthy(response) -> ConnectionResult.Reachable
        else -> NOT_POD_POCKET
    }

    private fun isHealthy(response: Response): Boolean {
        return try {
            val body = response.peekBody(MAX_BODY_BYTES).string()
            healthAdapter.fromJson(body)?.get("status") == "ok"
        } catch (e: IOException) {
            false
        } catch (e: JsonDataException) {
            false
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 64L * 1024
        val NOT_POD_POCKET = ConnectionResult.Unreachable(NOT_POD_POCKET_REASON)
        val healthAdapter = Moshi.Builder().build()
            .adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
    }
}
