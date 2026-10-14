package au.com.shiftyjelly.pocketcasts.musicassistant.output

import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import androidx.appcompat.view.ContextThemeWrapper
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import androidx.mediarouter.media.MediaRouteDescriptor
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaPlayer
import au.com.shiftyjelly.pocketcasts.views.extensions.addMusicAssistantRoutes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MusicAssistantDialogFilterTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), androidx.appcompat.R.style.Theme_AppCompat)
    private val router = MediaRouter.getInstance(context)
    private val kitchen = MaPlayer("kitchen", "Kitchen", "player", available = true)
    private val castSelector = MediaRouteSelector.Builder().addControlCategory(CAST_CATEGORY).build()
    private val provider = FakeProvider(context)
    private val button = MediaRouteButton(context).apply {
        routeSelector = castSelector
        addMusicAssistantRoutes()
    }

    @Before
    fun publish() {
        router.addProvider(provider)
        provider.publish(
            MaRouteProvider.routeDescriptor(kitchen, ready = true, description = "Music Assistant", name = "Kitchen (Music Assistant)"),
            castRoute("cast-kitchen", " kitchen"),
            castRoute("cast-bedroom", "Bedroom"),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        router.removeProvider(provider)
        shadowOf(Looper.getMainLooper()).idle()
        MediaRouter.resetGlobalRouter()
    }

    @Test
    fun `lists the speaker and hides the Chromecast with the same name`() {
        assertEquals(listOf("Bedroom", "Kitchen (Music Assistant)"), chooserNames())
    }

    @Test
    fun `keeps a Chromecast with the same name while it is selected`() {
        routeNamed(" kitchen").select()

        assertEquals(listOf(" kitchen", "Bedroom", "Kitchen (Music Assistant)"), chooserNames())
    }

    @Test
    fun `leaves the controller dialog to the previous factory`() {
        val controller = MediaRouteControllerDialogFragment()
        val previous = object : MediaRouteDialogFactory() {
            override fun onCreateControllerDialogFragment() = controller
        }
        val other = MediaRouteButton(context).apply { dialogFactory = previous }

        other.addMusicAssistantRoutes()

        assertSame(controller, other.dialogFactory.onCreateControllerDialogFragment())
    }

    private fun chooserNames(): List<String> {
        val dialog = button.dialogFactory.onCreateChooserDialogFragment().onCreateChooserDialog(context, null)
        dialog.routeSelector = button.routeSelector
        val routes = router.routes.toMutableList()
        dialog.onFilterRoutes(routes)
        return routes.map { it.name }.sorted()
    }

    private fun routeNamed(name: String) = router.routes.first { it.name == name }

    private fun castRoute(id: String, name: String) = MediaRouteDescriptor.Builder(id, name)
        .addControlFilter(IntentFilter().apply { addCategory(CAST_CATEGORY) })
        .build()

    private class FakeProvider(context: Context) : MediaRouteProvider(context) {
        fun publish(vararg routes: MediaRouteDescriptor) {
            descriptor = MediaRouteProviderDescriptor.Builder().addRoutes(routes.toList()).build()
        }
    }

    private companion object {
        const val CAST_CATEGORY = "com.google.android.gms.cast.CATEGORY_CAST/A1B2C3D4"
    }
}
