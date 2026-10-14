package au.com.shiftyjelly.pocketcasts.musicassistant.output

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
class MusicAssistantStarterTest {
    private val uncaught = mutableListOf<Throwable>()
    private val logged = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logged += message
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })

    @After
    fun tearDown() {
        Timber.uproot(tree)
        scope.cancel()
    }

    @Test
    fun `survives a failure while opening Music Assistant`() {
        Timber.plant(tree)
        val starter = MusicAssistantStarter(
            context = ApplicationProvider.getApplicationContext<Application>(),
            accounts = { error("secret-token-1234") },
            session = { error("unused") },
            output = { error("unused") },
            scope = scope,
        )

        starter.start()
        runBlocking { scope.coroutineContext.job.children.forEach { it.join() } }

        assertTrue(uncaught.isEmpty())
        assertEquals(listOf("Music Assistant could not start: IllegalStateException"), logged)
        assertFalse(logged.any { it.contains("secret-token-1234") })
    }
}
