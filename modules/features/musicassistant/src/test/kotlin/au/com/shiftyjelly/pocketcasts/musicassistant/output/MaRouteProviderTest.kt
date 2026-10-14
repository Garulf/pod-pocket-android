package au.com.shiftyjelly.pocketcasts.musicassistant.output

import androidx.mediarouter.media.MediaRouter
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaPlayer
import au.com.shiftyjelly.pocketcasts.views.extensions.MUSIC_ASSISTANT_ROUTE_CATEGORY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MaRouteProviderTest {
    private val kitchen = MaPlayer("media_player.kitchen", "Kitchen", "player", available = true, supportedFeatures = listOf("volume_set"), volumeLevel = 35)

    @Test
    fun `publishes a speaker as a remote route the output button can show`() {
        val route = MaRouteProvider.routeDescriptor(kitchen, ready = true, description = "Music Assistant")

        assertEquals("music-assistant:media_player.kitchen", route.id)
        assertEquals("Kitchen", route.name)
        assertEquals("Music Assistant", route.description)
        assertTrue(route.isEnabled)
        assertTrue(route.controlFilters.single().hasCategory(MUSIC_ASSISTANT_ROUTE_CATEGORY))
        assertEquals(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE, route.playbackType)
        assertEquals(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE, route.volumeHandling)
        assertEquals(35, route.volume)
        assertEquals(100, route.volumeMax)
    }

    @Test
    fun `greys out speakers while disconnected or unavailable`() {
        assertFalse(MaRouteProvider.routeDescriptor(kitchen, ready = false, description = "Reconnecting").isEnabled)
        assertFalse(MaRouteProvider.routeDescriptor(kitchen.copy(available = false), ready = true, description = "").isEnabled)
    }

    @Test
    fun `fixes the volume of speakers that cannot change it`() {
        val route = MaRouteProvider.routeDescriptor(kitchen.copy(supportedFeatures = emptyList()), ready = true, description = "")

        assertEquals(MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED, route.volumeHandling)
    }

    @Test
    fun `reads the player back from a route id`() {
        assertEquals("media_player.kitchen", MaRouteProvider.playerIdOf(MaRouteProvider.routeId("media_player.kitchen")))
        assertNull(MaRouteProvider.playerIdOf("cast-route-1234"))
    }
}
