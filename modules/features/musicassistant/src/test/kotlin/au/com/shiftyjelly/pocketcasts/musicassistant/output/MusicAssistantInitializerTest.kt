package au.com.shiftyjelly.pocketcasts.musicassistant.output

import android.app.Application
import android.os.Looper
import androidx.mediarouter.media.MediaRouter
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryPlaybackStore
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.media.MaMediaResolver
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// No JUnit Timeout rule: it would run the test off the main looper thread that MediaRouter requires. Every wait below is bounded.
@RunWith(RobolectricTestRunner::class)
class MusicAssistantInitializerTest {
    @After
    fun tearDown() {
        (ApplicationProvider.getApplicationContext<Application>() as? HiltLikeApplication)?.scope?.cancel()
        MediaRouter.resetGlobalRouter()
    }

    @Test
    fun `leaves an app without Hilt alone`() {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()

        MusicAssistantInitializer().create(ApplicationProvider.getApplicationContext())

        assertTrue(looper.isIdle)
    }

    @Test
    @Config(application = HiltLikeApplication::class)
    fun `starts Music Assistant once the Hilt app is up and opens the account store off the main thread`() {
        val app = ApplicationProvider.getApplicationContext<HiltLikeApplication>()

        MusicAssistantInitializer().create(app)
        assertTrue(app.accountStoreOpenedOnMain.isEmpty())

        val deadline = System.currentTimeMillis() + 10_000
        while (app.builtOutput?.routes == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertNotNull(app.builtOutput?.routes)
        assertEquals(listOf(false), app.accountStoreOpenedOnMain.toList())
        assertFalse(app.builtOutput!!.isActive.value)
    }

    class HiltLikeApplication :
        Application(),
        GeneratedComponentManager<Any> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val accountStoreOpenedOnMain = CopyOnWriteArrayList<Boolean>()
        private val accounts by lazy {
            accountStoreOpenedOnMain += Looper.myLooper() == Looper.getMainLooper()
            InMemoryAccountStore()
        }
        private val session by lazy { MaSession(scope, accounts, { MaConnection(OkHttpClient()) }) }

        @Volatile
        var builtOutput: MusicAssistantOutput? = null
        private val output by lazy {
            MusicAssistantOutput(
                session = session,
                resolver = MaMediaResolver(session, { emptyMap() }, { null }),
                playbackStore = InMemoryPlaybackStore(),
                scope = scope,
                notify = { },
            ).also { builtOutput = it }
        }
        private val starter by lazy { MusicAssistantStarter(this, { accounts }, { session }, { output }, scope) }

        override fun generatedComponent(): Any = object : GeneratedComponent, MusicAssistantEntryPoint {
            override fun starter() = starter
        }
    }
}
