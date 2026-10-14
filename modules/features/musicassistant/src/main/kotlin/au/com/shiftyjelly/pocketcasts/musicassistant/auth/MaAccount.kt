package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl

data class MaAccount(
    val serverUrl: HttpUrl,
    val token: String,
    val gpodderInstanceId: String?,
    val gpodderOwner: String? = null,
) {
    fun gpodderInstanceFor(podPocketUser: String?): String? = gpodderInstanceId?.takeIf { gpodderOwner == podPocketUser }

    override fun toString() = "MaAccount(serverUrl=$serverUrl, token=<redacted>, gpodderInstanceId=$gpodderInstanceId, gpodderOwner=<redacted>)"
}

interface MaAccountStore {
    val account: StateFlow<MaAccount?>

    fun save(account: MaAccount?)
}

fun interface PodPocketUser {
    fun key(): String?
}
