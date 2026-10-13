package au.com.shiftyjelly.pocketcasts.selfhosted

import android.content.SharedPreferences
import okhttp3.HttpUrl

class SharedPreferencesServerUrlStore(
    private val preferences: SharedPreferences,
) : ServerUrlStore {
    private val lock = Any()

    @Volatile
    private var loaded = false

    @Volatile
    private var current: HttpUrl? = null

    override val serverUrl: HttpUrl?
        get() {
            if (!loaded) {
                synchronized(lock) {
                    if (!loaded) {
                        current = preferences.getString(KEY_SERVER_URL, null)?.let(ServerUrls::parse)
                        loaded = true
                    }
                }
            }
            return current
        }

    override fun setServerUrl(url: HttpUrl?) {
        synchronized(lock) {
            val editor = preferences.edit()
            if (url == null) {
                editor.remove(KEY_SERVER_URL)
            } else {
                editor.putString(KEY_SERVER_URL, url.toString())
            }
            editor.apply()
            current = url
            loaded = true
        }
    }

    companion object {
        const val PREFERENCES_NAME = "pod_pocket_selfhosted"
        const val KEY_SERVER_URL = "server_url"
    }
}
