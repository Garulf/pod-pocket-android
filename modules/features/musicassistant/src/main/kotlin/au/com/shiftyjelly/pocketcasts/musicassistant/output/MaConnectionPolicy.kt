package au.com.shiftyjelly.pocketcasts.musicassistant.output

import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccount
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

class MaConnectionPolicy(
    private val session: MaSessionControl,
    private val appInForeground: Flow<Boolean>,
    private val outputActive: Flow<Boolean>,
    private val account: Flow<MaAccount?>,
) {
    fun launchIn(scope: CoroutineScope) = scope.launch {
        var started = false
        var connectedLogin: MaLogin? = null
        combine(appInForeground, outputActive, account) { foreground, active, current -> (foreground || active) to current?.let(::MaLogin) }
            .distinctUntilChanged()
            .collect { (wanted, current) ->
                when {
                    !wanted -> {
                        if (started) session.stop()
                        started = false
                    }

                    !started -> {
                        session.start()
                        started = true
                    }

                    current != connectedLogin -> session.restart()
                }
                connectedLogin = current
            }
    }
}

private data class MaLogin(val serverUrl: HttpUrl, val token: String) {
    constructor(account: MaAccount) : this(account.serverUrl, account.token)

    override fun toString() = "MaLogin(serverUrl=$serverUrl, token=<redacted>)"
}
