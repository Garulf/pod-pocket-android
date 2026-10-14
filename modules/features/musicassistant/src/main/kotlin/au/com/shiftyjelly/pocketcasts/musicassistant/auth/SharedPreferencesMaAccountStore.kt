package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SharedPreferencesMaAccountStore(
    private var preferences: SharedPreferences,
    private val onAccountChanged: () -> Unit = {},
    private val resetPreferences: () -> SharedPreferences = { preferences.also { it.edit().clear().commit() } },
) : MaAccountStore {
    private val state = MutableStateFlow(read())
    override val account: StateFlow<MaAccount?> = state.asStateFlow()

    override fun save(account: MaAccount?) {
        if (write(account)) {
            onAccountChanged()
        }
    }

    @Synchronized
    private fun write(account: MaAccount?): Boolean {
        val changed = account != state.value
        val editor = preferences.edit().clear()
        if (account != null) {
            editor.putString(KEY_SERVER_URL, account.serverUrl.toString())
            editor.putString(KEY_TOKEN, account.token)
            account.gpodderInstanceId?.let { editor.putString(KEY_GPODDER_INSTANCE, it) }
            account.gpodderOwner?.let { editor.putString(KEY_GPODDER_OWNER, it) }
        }
        editor.apply()
        state.value = account
        return changed
    }

    private fun read(): MaAccount? {
        return try {
            readStored()
        } catch (e: RuntimeException) {
            preferences = resetPreferences()
            null
        }
    }

    private fun readStored(): MaAccount? {
        val serverUrl = preferences.getString(KEY_SERVER_URL, null)?.toHttpUrlOrNull() ?: return null
        val token = preferences.getString(KEY_TOKEN, null) ?: return null
        return MaAccount(serverUrl, token, preferences.getString(KEY_GPODDER_INSTANCE, null), preferences.getString(KEY_GPODDER_OWNER, null))
    }

    companion object {
        const val PREFERENCES_NAME = "pod_pocket_music_assistant_account"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_GPODDER_INSTANCE = "gpodder_instance_id"
        const val KEY_GPODDER_OWNER = "gpodder_owner"
    }
}
