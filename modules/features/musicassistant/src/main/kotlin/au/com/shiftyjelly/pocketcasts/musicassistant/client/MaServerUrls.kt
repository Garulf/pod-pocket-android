package au.com.shiftyjelly.pocketcasts.musicassistant.client

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object MaServerUrls {
    const val DEFAULT_PORT = 8095

    fun parse(input: String): HttpUrl? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val hasScheme = trimmed.contains("://")
        val url = (if (hasScheme) trimmed else "http://$trimmed").toHttpUrlOrNull() ?: return null
        val typedPort = hasScheme || trimmed.substringBefore('/').substringAfterLast(']').contains(':')
        val basePath = url.encodedPathSegments.filter { it.isNotEmpty() }.joinToString("/")
        return url.newBuilder()
            .username("")
            .password("")
            .encodedPath("/$basePath")
            .query(null)
            .fragment(null)
            .apply { if (!typedPort) port(DEFAULT_PORT) }
            .build()
    }
}
