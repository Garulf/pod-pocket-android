package au.com.shiftyjelly.pocketcasts.views.extensions

import android.content.Context
import android.os.Bundle
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import androidx.mediarouter.app.MediaRouteDynamicChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter

const val MUSIC_ASSISTANT_ROUTE_CATEGORY = "net.garulf.podpocket.MUSIC_ASSISTANT"
const val MUSIC_ASSISTANT_PLAYER_NAME_EXTRA = "net.garulf.podpocket.MA_PLAYER_NAME"

fun MediaRouteSelector.withMusicAssistantRoutes(): MediaRouteSelector {
    return MediaRouteSelector.Builder(this)
        .addControlCategory(MUSIC_ASSISTANT_ROUTE_CATEGORY)
        .build()
}

fun MediaRouteButton.addMusicAssistantRoutes() {
    routeSelector = routeSelector.withMusicAssistantRoutes()
    if (dialogFactory !is MusicAssistantDialogFactory) {
        dialogFactory = MusicAssistantDialogFactory(dialogFactory)
    }
}

fun shouldHideRoute(
    routeName: String,
    isMusicAssistantRoute: Boolean,
    isSelected: Boolean,
    musicAssistantPlayerNames: List<String>,
): Boolean {
    if (isMusicAssistantRoute || isSelected) return false
    val name = routeName.trim()
    return musicAssistantPlayerNames.any { it.trim().equals(name, ignoreCase = true) }
}

private fun MediaRouter.RouteInfo.isMusicAssistant() = supportsControlCategory(MUSIC_ASSISTANT_ROUTE_CATEGORY)

private fun hidesDuplicate(context: Context, route: MediaRouter.RouteInfo): Boolean {
    val playerNames = MediaRouter.getInstance(context).routes
        .filter { it.isMusicAssistant() }
        .mapNotNull { it.extras?.getString(MUSIC_ASSISTANT_PLAYER_NAME_EXTRA) }
    return shouldHideRoute(route.name, route.isMusicAssistant(), route.isSelected, playerNames)
}

private class MusicAssistantDialogFactory(private val delegate: MediaRouteDialogFactory) : MediaRouteDialogFactory() {
    override fun onCreateChooserDialogFragment(): MediaRouteChooserDialogFragment = object : MediaRouteChooserDialogFragment() {
        override fun onCreateChooserDialog(context: Context, savedInstanceState: Bundle?): MediaRouteChooserDialog {
            return object : MediaRouteChooserDialog(context) {
                override fun onFilterRoute(route: MediaRouter.RouteInfo) = super.onFilterRoute(route) && !hidesDuplicate(context, route)
            }
        }

        override fun onCreateDynamicChooserDialog(context: Context): MediaRouteDynamicChooserDialog {
            return object : MediaRouteDynamicChooserDialog(context) {
                override fun onFilterRoute(route: MediaRouter.RouteInfo) = super.onFilterRoute(route) && !hidesDuplicate(context, route)
            }
        }
    }

    override fun onCreateControllerDialogFragment(): MediaRouteControllerDialogFragment = delegate.onCreateControllerDialogFragment()
}
