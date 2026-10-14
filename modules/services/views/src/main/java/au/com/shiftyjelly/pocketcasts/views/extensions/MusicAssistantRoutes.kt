package au.com.shiftyjelly.pocketcasts.views.extensions

import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouteSelector

const val MUSIC_ASSISTANT_ROUTE_CATEGORY = "net.garulf.podpocket.MUSIC_ASSISTANT"

fun MediaRouteSelector.withMusicAssistantRoutes(): MediaRouteSelector {
    return MediaRouteSelector.Builder(this)
        .addControlCategory(MUSIC_ASSISTANT_ROUTE_CATEGORY)
        .build()
}

fun MediaRouteButton.addMusicAssistantRoutes() {
    routeSelector = routeSelector.withMusicAssistantRoutes()
}
