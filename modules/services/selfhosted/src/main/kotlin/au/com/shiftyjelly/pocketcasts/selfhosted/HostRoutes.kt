package au.com.shiftyjelly.pocketcasts.selfhosted

sealed interface HostRoute {
    data class Rewrite(val prefix: String) : HostRoute

    data object Block : HostRoute

    data object PassThrough : HostRoute
}

object HostRoutes {
    private val pocketCastsDomains = listOf("pocketcasts.com", "pocketcasts.net")

    private val prefixBySubdomain = mapOf(
        "api" to "api",
        "refresh" to "refresh",
        "cache" to "cache",
        "podcast-api" to "cache",
        "search" to "search",
        "static" to "static",
        "lists" to "lists",
        "sharing" to "sharing",
        "shownotes" to "shownotes",
        "web-feeds-api" to "web-feeds",
        "pcast" to "short",
    )

    private const val SHORT_LINK_DOMAIN = "pca.st"

    private val telemetryDomains = listOf(
        "wordpress.com",
        "wp.com",
        "automattic.com",
        "gravatar.com",
        "sentry.io",
    )

    fun routeFor(host: String): HostRoute {
        val normalized = host.lowercase().trimEnd('.')
        if (normalized.isWithin(SHORT_LINK_DOMAIN)) {
            return HostRoute.Rewrite("short")
        }
        val pocketCastsDomain = pocketCastsDomains.firstOrNull { normalized.isWithin(it) }
        if (pocketCastsDomain != null) {
            val subdomain = normalized.removeSuffix(pocketCastsDomain).removeSuffix(".")
            val prefix = prefixBySubdomain[subdomain] ?: return HostRoute.Block
            return HostRoute.Rewrite(prefix)
        }
        if (telemetryDomains.any { normalized.isWithin(it) }) {
            return HostRoute.Block
        }
        return HostRoute.PassThrough
    }

    private fun String.isWithin(domain: String) = this == domain || endsWith(".$domain")
}
