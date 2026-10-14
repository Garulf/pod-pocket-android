package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SharedPreferencesMaAccountStore(
    private val preferences: SharedPreferences,
    private val onAccountChanged: () -> Unit = {},
) : MaAccountStore {
    private val state = MutableStateFlow(read())
    override val account: StateFlow<MaAccount?> = state.asStateFlow()

    override fun save(account: MaAccount?) {
        write(account)
        onAccountChanged()
    }

    @Synchronized
    private fun write(account: MaAccount?) {
        val editor = preferences.edit().clear()
        if (account != null) {
            editor.putString(KEY_SERVER_URL, account.serverUrl.toString())
            editor.putString(KEY_TOKEN, account.token)
            account.gpodderInstanceId?.let { editor.putString(KEY_GPODDER_INSTANCE, it) }
        }
        editor.apply()
        state.value = account
    }

    private fun read(): MaAccount? {
        val serverUrl = preferences.getString(KEY_SERVER_URL, null)?.toHttpUrlOrNull() ?: return null
        val token = preferences.getString(KEY_TOKEN, null) ?: return null
        return MaAccount(serverUrl, token, preferences.getString(KEY_GPODDER_INSTANCE, null))
    }

    companion object {
        const val PREFERENCES_NAME = "pod_pocket_music_assistant_account"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_GPODDER_INSTANCE = "gpodder_instance_id"
    }
}
