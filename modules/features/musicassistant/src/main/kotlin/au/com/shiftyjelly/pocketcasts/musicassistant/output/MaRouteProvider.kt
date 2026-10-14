package au.com.shiftyjelly.pocketcasts.musicassistant.output

import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
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
import au.com.shiftyjelly.pocketcasts.views.extensions.MUSIC_ASSISTANT_PLAYER_NAME_EXTRA
import au.com.shiftyjelly.pocketcasts.views.extensions.MUSIC_ASSISTANT_ROUTE_CATEGORY
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MaRouteProvider(
    context: Context,
    private val session: MaSession,
    private val output: MusicAssistantOutput,
    private val scope: CoroutineScope,
    private val describe: (MaSessionState) -> String = { state -> context.getString(routeLabel(state)) },
    private val mediaSession: () -> MediaSessionCompat? = { null },
    private val routeName: (MaPlayer) -> String = { player -> context.getString(R.string.pod_pocket_ma_route_name, player.name) },
) : MediaRouteProvider(context),
    OutputRoutes {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val router: MediaRouter by lazy { MediaRouter.getInstance(context) }
    private val selectionDeadline = Runnable { giveUpSelection() }

    private var pendingSelection: String? = null
    private var awaitedSelection: String? = null

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) { "MaRouteProvider must be created on the main thread" }
    }

    fun start() {
        router.addProvider(this)
        router.addCallback(
            MediaRouteSelector.Builder().addControlCategory(MUSIC_ASSISTANT_ROUTE_CATEGORY).build(),
            object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = selectPending()

                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = selectPending()

                override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = handVolumeKeysToSelectedSpeaker()

                override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = handVolumeKeysToSelectedSpeaker()
            },
        )
        scope.launch(Dispatchers.Main) {
            combine(session.players, session.state, output.activePlayerId) { players, state, selected ->
                publishedRoutes(outputPlayers(players.values), state, selected)
            }.distinctUntilChanged().collect { routes ->
                descriptor = MediaRouteProviderDescriptor.Builder()
                    .addRoutes(routes.map { routeDescriptor(it.player, it.ready, it.description, routeName(it.player)) })
                    .build()
                handVolumeKeysToSelectedSpeaker()
            }
        }
    }

    override fun selectPlayer(playerId: String) {
        mainHandler.post {
            pendingSelection = playerId
            awaitedSelection = playerId
            mainHandler.removeCallbacks(selectionDeadline)
            mainHandler.postDelayed(selectionDeadline, SELECTION_TIMEOUT_MS)
            selectPending()
        }
    }

    override fun selectPhone(playerId: String) {
        mainHandler.post {
            if (awaitedSelection == playerId) {
                pendingSelection = null
                confirmSelection(playerId)
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
                confirmSelection(playerId)
                output.onRouteSelected(playerId)
            }

            override fun onUnselect(reason: Int) {
                output.onRouteUnselected(playerId)
            }

            override fun onSetVolume(volume: Int) {
                setVolume(playerId, volume)
            }

            override fun onUpdateVolume(delta: Int) {
                when {
                    delta > 0 -> sendVolume("players/cmd/volume_up", mapOf("player_id" to playerId))
                    delta < 0 -> sendVolume("players/cmd/volume_down", mapOf("player_id" to playerId))
                }
            }
        }
    }

    private fun selectPending() {
        val playerId = pendingSelection ?: return
        val route = router.routes.firstOrNull { isOurs(it, playerId) && it.isEnabled } ?: return
        pendingSelection = null
        if (route.isSelected) {
            confirmSelection(playerId)
        } else {
            route.select()
        }
    }

    private fun confirmSelection(playerId: String) {
        if (awaitedSelection == playerId) {
            awaitedSelection = null
            mainHandler.removeCallbacks(selectionDeadline)
        }
    }

    private fun giveUpSelection() {
        val playerId = awaitedSelection ?: return
        awaitedSelection = null
        pendingSelection = null
        output.onRouteSelectionAbandoned(playerId)
    }

    private fun publishedRoutes(players: List<MaPlayer>, state: MaSessionState, selected: String?): List<PublishedRoute> {
        return when (state) {
            is MaSessionState.Connected -> players.map { PublishedRoute(it, ready = true, describe(state)) }
            is MaSessionState.Connecting, is MaSessionState.Reconnecting, MaSessionState.SignInRequired ->
                players.map { PublishedRoute(it, ready = it.playerId == selected, describe(state)) }
            MaSessionState.Disconnected, MaSessionState.NotConfigured ->
                players.filter { it.playerId == selected }.map { PublishedRoute(it, ready = true, describe(state)) }
        }
    }

    private data class PublishedRoute(val player: MaPlayer, val ready: Boolean, val description: String)

    private fun isSelected(route: MediaRouter.RouteInfo, playerId: String) = route.isSelected && isOurs(route, playerId)

    private fun isOurs(route: MediaRouter.RouteInfo, playerId: String): Boolean {
        return route.provider.providerInstance === this && route.id.endsWith(":${routeId(playerId)}")
    }

    private fun handVolumeKeysToSelectedSpeaker() {
        val appSession = mediaSession() ?: return
        val routerHoldsAppSession = router.mediaSessionToken == appSession.sessionToken
        val speakerSelected = router.selectedRoute.provider.providerInstance === this
        when {
            speakerSelected && !routerHoldsAppSession -> router.setMediaSessionCompat(appSession)
            !speakerSelected && routerHoldsAppSession -> router.setMediaSessionCompat(null)
        }
    }

    private fun setVolume(playerId: String, volume: Int) {
        sendVolume("players/cmd/volume_set", mapOf("player_id" to playerId, "volume_level" to volume.coerceIn(0, MAX_VOLUME)))
    }

    private fun sendVolume(command: String, args: Map<String, Any?>) {
        scope.launch {
            try {
                session.call(command, args)
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

        private fun routeLabel(state: MaSessionState): Int = when (state) {
            is MaSessionState.Connected -> R.string.pod_pocket_ma_route_description
            is MaSessionState.Connecting -> R.string.pod_pocket_ma_status_connecting
            MaSessionState.SignInRequired -> R.string.pod_pocket_ma_route_sign_in
            MaSessionState.NotConfigured, MaSessionState.Disconnected, is MaSessionState.Reconnecting -> R.string.pod_pocket_ma_status_reconnecting
        }

        fun playerIdOf(routeId: String): String? = routeId.takeIf { it.startsWith(ROUTE_PREFIX) }?.removePrefix(ROUTE_PREFIX)

        fun routeDescriptor(player: MaPlayer, ready: Boolean, description: String, name: String): MediaRouteDescriptor {
            return MediaRouteDescriptor.Builder(routeId(player.playerId), name)
                .setDescription(description)
                .setExtras(Bundle().apply { putString(MUSIC_ASSISTANT_PLAYER_NAME_EXTRA, player.name) })
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
