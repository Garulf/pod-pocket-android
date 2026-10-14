package au.com.shiftyjelly.pocketcasts.musicassistant.output

import android.content.Context
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteDescriptor
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import au.com.shiftyjelly.pocketcasts.musicassistant.R
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaCommandException
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaPlayer
import au.com.shiftyjelly.pocketcasts.views.extensions.MUSIC_ASSISTANT_ROUTE_CATEGORY
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MaRouteProvider(
    context: Context,
    private val session: MaSession,
    private val output: MusicAssistantOutput,
    private val scope: CoroutineScope,
    private val describe: (connected: Boolean) -> String = { connected ->
        context.getString(if (connected) R.string.pod_pocket_ma_route_description else R.string.pod_pocket_ma_status_reconnecting)
    },
) : MediaRouteProvider(context),
    OutputRoutes {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val router: MediaRouter by lazy { MediaRouter.getInstance(context) }
    private val selectionDeadline = Runnable { giveUpSelection() }

    private var pendingSelection: String? = null

    fun start() {
        router.addProvider(this)
        router.addCallback(
            MediaRouteSelector.Builder().addControlCategory(MUSIC_ASSISTANT_ROUTE_CATEGORY).build(),
            object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = selectPending()

                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = selectPending()
            },
        )
        scope.launch(Dispatchers.Main) {
            combine(session.players, session.state, output.activePlayerId) { players, state, selected ->
                val connected = state is MaSessionState.Connected
                val description = describe(connected)
                outputPlayers(players.values).map { routeDescriptor(it, ready = connected || it.playerId == selected, description) }
            }.collect { routes ->
                descriptor = MediaRouteProviderDescriptor.Builder().addRoutes(routes).build()
            }
        }
    }

    override fun selectPlayer(playerId: String) {
        mainHandler.post {
            pendingSelection = playerId
            mainHandler.removeCallbacks(selectionDeadline)
            mainHandler.postDelayed(selectionDeadline, SELECTION_TIMEOUT_MS)
            selectPending()
        }
    }

    override fun selectPhone(playerId: String) {
        mainHandler.post {
            if (pendingSelection == playerId) {
                clearPendingSelection()
            }
            if (isSelected(router.selectedRoute, playerId)) {
                router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
            } else {
                output.onRouteUnselected(playerId)
            }
        }
    }

    override fun onCreateRouteController(routeId: String): RouteController? {
        val playerId = playerIdOf(routeId) ?: return null
        return object : RouteController() {
            override fun onSelect() {
                output.onRouteSelected(playerId)
            }

            override fun onUnselect(reason: Int) {
                output.onRouteUnselected(playerId)
            }

            override fun onSetVolume(volume: Int) {
                setVolume(playerId, volume)
            }

            override fun onUpdateVolume(delta: Int) {
                val current = session.players.value[playerId]?.volumeLevel ?: return
                setVolume(playerId, current + delta)
            }
        }
    }

    private fun selectPending() {
        val playerId = pendingSelection ?: return
        val route = router.routes.firstOrNull { isOurs(it, playerId) && it.isEnabled } ?: return
        clearPendingSelection()
        if (!route.isSelected) {
            route.select()
        }
    }

    private fun giveUpSelection() {
        val playerId = pendingSelection ?: return
        pendingSelection = null
        output.onRouteSelectionAbandoned(playerId)
    }

    private fun clearPendingSelection() {
        pendingSelection = null
        mainHandler.removeCallbacks(selectionDeadline)
    }

    private fun isSelected(route: MediaRouter.RouteInfo, playerId: String) = route.isSelected && isOurs(route, playerId)

    private fun isOurs(route: MediaRouter.RouteInfo, playerId: String): Boolean {
        return route.provider.providerInstance === this && route.id.endsWith(":${routeId(playerId)}")
    }

    private fun setVolume(playerId: String, volume: Int) {
        scope.launch {
            try {
                session.call("players/cmd/volume_set", mapOf("player_id" to playerId, "volume_level" to volume.coerceIn(0, MAX_VOLUME)))
            } catch (e: MaCommandException) {
                return@launch
            } catch (e: IOException) {
                return@launch
            }
        }
    }

    companion object {
        private const val ROUTE_PREFIX = "music-assistant:"
        const val MAX_VOLUME = 100
        const val SELECTION_TIMEOUT_MS = 5_000L

        fun routeId(playerId: String) = "$ROUTE_PREFIX$playerId"

        fun playerIdOf(routeId: String): String? = routeId.takeIf { it.startsWith(ROUTE_PREFIX) }?.removePrefix(ROUTE_PREFIX)

        fun routeDescriptor(player: MaPlayer, ready: Boolean, description: String): MediaRouteDescriptor {
            return MediaRouteDescriptor.Builder(routeId(player.playerId), player.name)
                .setDescription(description)
                .addControlFilter(IntentFilter().apply { addCategory(MUSIC_ASSISTANT_ROUTE_CATEGORY) })
                .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                .setDeviceType(MediaRouter.RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER)
                .setVolumeHandling(if (player.canSetVolume) MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE else MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED)
                .setVolumeMax(MAX_VOLUME)
                .setVolume(player.volumeLevel ?: 0)
                .setEnabled(ready && player.available)
                .build()
        }
    }
}
