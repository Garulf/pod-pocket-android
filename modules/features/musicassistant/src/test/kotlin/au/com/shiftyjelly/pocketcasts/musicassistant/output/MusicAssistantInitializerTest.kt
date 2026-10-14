package au.com.shiftyjelly.pocketcasts.musicassistant.output

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MusicAssistantInitializerTest {
    @Test
    fun `leaves an app without Hilt alone`() {
        MusicAssistantInitializer().create(ApplicationProvider.getApplicationContext())

        shadowOf(Looper.getMainLooper()).idle()
    }
}
