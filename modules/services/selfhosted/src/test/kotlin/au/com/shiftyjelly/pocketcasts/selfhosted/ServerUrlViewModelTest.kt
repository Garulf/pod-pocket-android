package au.com.shiftyjelly.pocketcasts.selfhosted

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerUrlViewModelTest {
    private val store = InMemoryServerUrlStore()
    private val checkedUrls = mutableListOf<HttpUrl>()
    private var nextResult: ConnectionResult = ConnectionResult.Reachable
    private val checker = ServerConnectionChecker { url ->
        checkedUrls += url
        nextResult
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts with the stored server`() {
        store.setServerUrl("https://pod.example.com/".toHttpUrl())

        val viewModel = ServerUrlViewModel(store, checker)

        assertEquals("https://pod.example.com/", viewModel.state.value.text)
    }

    @Test
    fun `keeps typed text out of the store until the server is checked`() {
        store.setServerUrl("https://old.example.com/".toHttpUrl())
        val viewModel = ServerUrlViewModel(store, checker)

        viewModel.updateText("pod.example.com")
        viewModel.updateText("https://")

        assertEquals("https://old.example.com/".toHttpUrl(), store.serverUrl)
        assertEquals("https://", viewModel.state.value.text)
        assertEquals(ServerStatus.Unchecked, viewModel.state.value.status)
    }

    @Test
    fun `flags invalid text only when asked`() {
        val viewModel = ServerUrlViewModel(store, checker)
        viewModel.updateText("ftp://example.com")

        assertFalse(viewModel.hasValidUrl())
        assertEquals(ServerStatus.Invalid, viewModel.state.value.status)
    }

    @Test
    fun `continues once the server answers`() {
        val viewModel = ServerUrlViewModel(store, checker)
        var continued = false
        viewModel.updateText("pod.example.com")

        viewModel.checkConnection { continued = true }

        assertTrue(continued)
        assertEquals("https://pod.example.com/".toHttpUrl(), store.serverUrl)
        assertEquals(ServerStatus.Reachable("pod.example.com"), viewModel.state.value.status)
        assertEquals(listOf("https://pod.example.com/".toHttpUrl()), checkedUrls)
    }

    @Test
    fun `stays put when the server does not answer`() {
        nextResult = ConnectionResult.Unreachable("HTTP 502")
        store.setServerUrl("https://old.example.com/".toHttpUrl())
        val viewModel = ServerUrlViewModel(store, checker)
        var continued = false
        viewModel.updateText("pod.example.com")

        viewModel.checkConnection { continued = true }

        assertFalse(continued)
        assertEquals("https://old.example.com/".toHttpUrl(), store.serverUrl)
        assertEquals(ServerStatus.Unreachable("HTTP 502"), viewModel.state.value.status)
    }

    @Test
    fun `does not contact anything for invalid text`() {
        val viewModel = ServerUrlViewModel(store, checker)
        viewModel.updateText("not a url at all")

        viewModel.checkConnection { error("must not continue") }

        assertEquals(ServerStatus.Invalid, viewModel.state.value.status)
        assertTrue(checkedUrls.isEmpty())
        assertNull(store.serverUrl)
    }

    @Test
    fun `ignores taps while a check is running`() {
        val pending = CompletableDeferred<ConnectionResult>()
        val slowChecker = ServerConnectionChecker { url ->
            checkedUrls += url
            pending.await()
        }
        val viewModel = ServerUrlViewModel(store, slowChecker)
        var continued = 0
        viewModel.updateText("pod.example.com")

        viewModel.checkConnection { continued++ }
        viewModel.checkConnection { continued++ }
        pending.complete(ConnectionResult.Reachable)

        assertEquals(1, checkedUrls.size)
        assertEquals(1, continued)
    }

    @Test
    fun `discards a result for text that has since changed`() {
        val pending = CompletableDeferred<ConnectionResult>()
        val viewModel = ServerUrlViewModel(store, { pending.await() })
        var continued = false
        viewModel.updateText("pod.example.com")
        viewModel.checkConnection { continued = true }

        viewModel.updateText("other.example.com")
        pending.complete(ConnectionResult.Reachable)

        assertFalse(continued)
        assertNull(store.serverUrl)
        assertEquals("other.example.com", viewModel.state.value.text)
        assertEquals(ServerStatus.Unchecked, viewModel.state.value.status)
    }

    @Test
    fun `recovers when the checker throws`() {
        val viewModel = ServerUrlViewModel(store, { throw IllegalStateException("boom") })
        var continued = false
        viewModel.updateText("pod.example.com")

        viewModel.checkConnection { continued = true }

        assertFalse(continued)
        assertNull(store.serverUrl)
        assertEquals(ServerStatus.Unreachable("boom"), viewModel.state.value.status)
    }

    private class InMemoryServerUrlStore : ServerUrlStore {
        override var serverUrl: HttpUrl? = null
            private set

        override fun setServerUrl(url: HttpUrl?) {
            serverUrl = url
        }
    }
}
