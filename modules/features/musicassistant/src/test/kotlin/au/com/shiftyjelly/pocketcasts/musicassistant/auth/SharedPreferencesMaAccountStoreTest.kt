package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.security.GeneralSecurityException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesMaAccountStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences(SharedPreferencesMaAccountStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val account = MaAccount("http://192.168.1.20:8095/".toHttpUrl(), "long.lived.jwt", "gpodder--Xk3dTq9a")

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `keeps the account across instances`() {
        SharedPreferencesMaAccountStore(preferences).save(account)

        assertEquals(account, SharedPreferencesMaAccountStore(preferences).account.value)
    }

    @Test
    fun `stores the server, token and provider and nothing else`() {
        SharedPreferencesMaAccountStore(preferences).save(account)

        assertEquals(
            mapOf(
                SharedPreferencesMaAccountStore.KEY_SERVER_URL to "http://192.168.1.20:8095/",
                SharedPreferencesMaAccountStore.KEY_TOKEN to "long.lived.jwt",
                SharedPreferencesMaAccountStore.KEY_GPODDER_INSTANCE to "gpodder--Xk3dTq9a",
            ),
            preferences.all,
        )
    }

    @Test
    fun `forgets the account on sign out`() {
        val store = SharedPreferencesMaAccountStore(preferences)
        store.save(account)

        store.save(null)

        assertNull(store.account.value)
        assertEquals(emptyMap<String, Any?>(), preferences.all)
    }

    @Test
    fun `drops the provider when none is chosen`() {
        val store = SharedPreferencesMaAccountStore(preferences)
        store.save(account)

        store.save(account.copy(gpodderInstanceId = null))

        assertNull(SharedPreferencesMaAccountStore(preferences).account.value?.gpodderInstanceId)
    }

    @Test
    fun `tells the session after an account is saved and after it is cleared`() {
        val seen = mutableListOf<MaAccount?>()
        lateinit var store: SharedPreferencesMaAccountStore
        store = SharedPreferencesMaAccountStore(preferences) { seen += store.account.value }

        store.save(account)
        store.save(null)

        assertEquals(listOf(account, null), seen)
    }

    @Test
    fun `signs the user out when the encrypted keyset is lost`() {
        val name = "lost_keyset_preferences"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("stale", "value").commit()
        var attempts = 0

        val recovered = openOrReset(context, name) {
            attempts++
            if (attempts == 1) throw GeneralSecurityException("keyset lost") else context.getSharedPreferences(name, Context.MODE_PRIVATE)
        }

        assertEquals(2, attempts)
        assertEquals(emptyMap<String, Any?>(), recovered.all)
    }

    @Test
    fun `resets once for an unreadable file and then gives up`() {
        var attempts = 0

        assertThrows(IOException::class.java) {
            openOrReset(context, "unreadable_preferences") {
                attempts++
                throw IOException("unreadable")
            }
        }

        assertEquals(2, attempts)
    }
}
