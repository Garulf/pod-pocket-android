package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl

data class MaAccount(
    val serverUrl: HttpUrl,
    val token: String,
    val gpodderInstanceId: String?,
)

interface MaAccountStore {
    val account: StateFlow<MaAccount?>

    fun save(account: MaAccount?)
}
