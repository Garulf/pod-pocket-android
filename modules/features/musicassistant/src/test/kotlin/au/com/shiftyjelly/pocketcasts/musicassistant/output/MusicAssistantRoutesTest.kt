package au.com.shiftyjelly.pocketcasts.musicassistant.output

import androidx.mediarouter.media.MediaRouteSelector
import au.com.shiftyjelly.pocketcasts.views.extensions.MUSIC_ASSISTANT_ROUTE_CATEGORY
import au.com.shiftyjelly.pocketcasts.views.extensions.withMusicAssistantRoutes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MusicAssistantRoutesTest {
    @Test
    fun `keeps the Cast routes and adds Music Assistant speakers`() {
        val cast = MediaRouteSelector.Builder().addControlCategory(CAST_CATEGORY).build()

        val merged = cast.withMusicAssistantRoutes()

        assertEquals(listOf(CAST_CATEGORY, MUSIC_ASSISTANT_ROUTE_CATEGORY), merged.controlCategories)
    }

    @Test
    fun `adds the category once`() {
        val merged = MediaRouteSelector.EMPTY.withMusicAssistantRoutes().withMusicAssistantRoutes()

        assertEquals(listOf(MUSIC_ASSISTANT_ROUTE_CATEGORY), merged.controlCategories)
    }

    private companion object {
        const val CAST_CATEGORY = "com.google.android.gms.cast.CATEGORY_CAST/A1B2C3D4"
    }
}
