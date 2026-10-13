package au.com.shiftyjelly.pocketcasts.selfhosted

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrls {
    fun parse(input: String): HttpUrl? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (HostRoutes.routeFor(url.host) != HostRoute.PassThrough) {
            return null
        }
        val basePath = url.encodedPathSegments.filter { it.isNotEmpty() }.joinToString("/")
        return url.newBuilder()
            .username("")
            .password("")
            .encodedPath("/$basePath")
            .query(null)
            .fragment(null)
            .build()
    }
}
