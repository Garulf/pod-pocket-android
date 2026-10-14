package au.com.shiftyjelly.pocketcasts.musicassistant.output

import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccount
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionControl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MaConnectionPolicyTest {
    private val calls = mutableListOf<String>()
    private val session = object : MaSessionControl {
        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun restart() {
            calls += "restart"
        }
    }
    private val foreground = MutableStateFlow(false)
    private val active = MutableStateFlow(false)
    private val account = MutableStateFlow<MaAccount?>(null)

    @Test
    fun `connects while the app is open or a speaker is playing`() = runTest(UnconfinedTestDispatcher()) {
        val job = MaConnectionPolicy(session, foreground, active, account).launchIn(backgroundScope)

        foreground.value = true
        active.value = true
        foreground.value = false
        active.value = false

        assertEquals(listOf("start", "stop"), calls)
        job.cancel()
    }

    @Test
    fun `reconnects when the account changes`() = runTest(UnconfinedTestDispatcher()) {
        MaConnectionPolicy(session, foreground, active, account).launchIn(backgroundScope)
        foreground.value = true

        account.value = MaAccount("http://ma.local:8095/".toHttpUrl(), "token", null)
        account.value = MaAccount("http://ma.local:8095/".toHttpUrl(), "token", "gpodder--abc")

        assertEquals(listOf("start", "restart", "restart"), calls)
    }
}
