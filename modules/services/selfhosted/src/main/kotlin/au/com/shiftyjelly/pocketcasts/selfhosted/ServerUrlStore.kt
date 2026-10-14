package au.com.shiftyjelly.pocketcasts.selfhosted

import okhttp3.HttpUrl

interface ServerUrlStore {
    val serverUrl: HttpUrl?

    fun setServerUrl(url: HttpUrl?)
}
