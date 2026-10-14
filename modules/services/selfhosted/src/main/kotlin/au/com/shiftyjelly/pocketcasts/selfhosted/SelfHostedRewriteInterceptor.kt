package au.com.shiftyjelly.pocketcasts.selfhosted

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

class SelfHostedRewriteInterceptor(
    private val serverUrl: () -> HttpUrl?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return when (val route = HostRoutes.routeFor(request.url.host)) {
            HostRoute.PassThrough -> chain.proceed(request)
            HostRoute.Block -> throw BlockedHostException(request.url.host)
            is HostRoute.Rewrite -> {
                val server = serverUrl() ?: throw ServerNotConfiguredException()
                val rewritten = rewriteToServer(request.url, server, route.prefix)
                chain.proceed(request.newBuilder().url(rewritten).build())
            }
        }
    }
}

internal fun rewriteToServer(original: HttpUrl, server: HttpUrl, prefix: String): HttpUrl {
    val segments = server.encodedPathSegments.filter { it.isNotEmpty() } + prefix + original.encodedPathSegments
    return server.newBuilder()
        .encodedPath("/" + segments.joinToString("/"))
        .encodedQuery(original.encodedQuery)
        .build()
}

class BlockedHostException(host: String) : IOException(blockedHostMessage(host))

internal fun blockedHostMessage(host: String) = "Requests to $host are disabled in Pod Pocket"

class ServerNotConfiguredException : IOException("No Pod Pocket server URL is set")
