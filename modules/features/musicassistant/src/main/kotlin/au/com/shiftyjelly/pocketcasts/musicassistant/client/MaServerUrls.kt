package au.com.shiftyjelly.pocketcasts.musicassistant.client

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object MaServerUrls {
    const val DEFAULT_PORT = 8095
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    fun parse(input: String): HttpUrl? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val hasScheme = SCHEME.containsMatchIn(trimmed)
        val url = (if (hasScheme) trimmed else "http://$trimmed").toHttpUrlOrNull() ?: return null
        val typedPort = hasScheme || hasPort(trimmed)
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

    private fun hasPort(bareInput: String): Boolean {
        val authority = bareInput.takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@').substringAfterLast(']').contains(':')
    }
}
