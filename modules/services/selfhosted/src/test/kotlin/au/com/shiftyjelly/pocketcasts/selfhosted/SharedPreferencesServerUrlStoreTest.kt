package au.com.shiftyjelly.pocketcasts.selfhosted

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesServerUrlStoreTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(SharedPreferencesServerUrlStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `starts without a server`() {
        assertNull(SharedPreferencesServerUrlStore(preferences).serverUrl)
    }

    @Test
    fun `keeps the server across instances`() {
        SharedPreferencesServerUrlStore(preferences).setServerUrl("https://pod.example.com/podpocket".toHttpUrl())

        assertEquals("https://pod.example.com/podpocket".toHttpUrl(), SharedPreferencesServerUrlStore(preferences).serverUrl)
    }

    @Test
    fun `forgets the server when cleared`() {
        val store = SharedPreferencesServerUrlStore(preferences)
        store.setServerUrl("https://pod.example.com/".toHttpUrl())

        store.setServerUrl(null)

        assertNull(store.serverUrl)
        assertNull(SharedPreferencesServerUrlStore(preferences).serverUrl)
    }

    @Test
    fun `ignores a stored value that is not a server URL`() {
        preferences.edit().putString(SharedPreferencesServerUrlStore.KEY_SERVER_URL, "https://api.pocketcasts.com/").commit()

        assertNull(SharedPreferencesServerUrlStore(preferences).serverUrl)
    }
}
