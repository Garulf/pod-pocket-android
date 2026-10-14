package au.com.shiftyjelly.pocketcasts.musicassistant.settings

import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccount
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaSignInResult
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaTokenRevoker
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicAssistantSettingsViewModelTest {
    private val accounts = InMemoryAccountStore()
    private val attempts = mutableListOf<Triple<HttpUrl, String, String>>()
    private val revoked = mutableListOf<MaAccount?>()
    private val accountWhenRevoking = mutableListOf<MaAccount?>()
    private var result: MaSignInResult = MaSignInResult.SignedIn("long.lived.jwt", listOf(GPODDER))
    private val session = MaSession(TestScope(), accounts, { MaConnection(OkHttpClient()) })
    private lateinit var viewModel: MusicAssistantSettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = createViewModel { server, username, password ->
            attempts += Triple(server, username, password)
            result
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stores the token and forgets the password`() {
        fillIn()

        viewModel.signIn()

        assertEquals(listOf(Triple("http://192.168.1.20:8095/".toHttpUrl(), "alice", "hunter2")), attempts)
        assertEquals(MaAccount("http://192.168.1.20:8095/".toHttpUrl(), "long.lived.jwt", "gpodder--Xk3dTq9a"), accounts.account.value)
        assertEquals(SignInForm(), viewModel.state.value.form)
    }

    @Test
    fun `asks which gPodder provider to use when there are several`() {
        val other = GPODDER.copy(name = "gPodder (old server)", instanceId = "gpodder--Q2w3E4r5")
        result = MaSignInResult.SignedIn("long.lived.jwt", listOf(GPODDER, other))
        fillIn()

        viewModel.signIn()

        assertNull(accounts.account.value?.gpodderInstanceId)
        assertEquals(listOf(GPODDER, other), viewModel.state.value.form.providerChoices)
        viewModel.chooseProvider("gpodder--Q2w3E4r5")
        assertEquals("gpodder--Q2w3E4r5", accounts.account.value?.gpodderInstanceId)
        assertTrue(viewModel.state.value.form.providerChoices.isEmpty())
    }

    @Test
    fun `shows why Music Assistant refused`() {
        result = MaSignInResult.Rejected("Invalid username or password")
        fillIn()

        viewModel.signIn()

        assertEquals(SignInError.Rejected("Invalid username or password"), viewModel.state.value.form.error)
        assertNull(accounts.account.value)
    }

    @Test
    fun `does not try an address it cannot read`() {
        fillIn(server = "ftp://music.local")

        viewModel.signIn()

        assertEquals(SignInError.InvalidAddress, viewModel.state.value.form.error)
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `ignores a second tap while signing in`() {
        val pending = CompletableDeferred<MaSignInResult>()
        viewModel = createViewModel { server, username, password ->
            attempts += Triple(server, username, password)
            pending.await()
        }
        fillIn()

        viewModel.signIn()
        viewModel.signIn()
        pending.complete(MaSignInResult.Unreachable("timeout"))

        assertEquals(1, attempts.size)
        assertEquals(SignInError.Unreachable("timeout"), viewModel.state.value.form.error)
    }

    @Test
    fun `signs out`() {
        fillIn()
        viewModel.signIn()

        viewModel.signOut()

        assertNull(accounts.account.value)
        assertNull(viewModel.state.value.account)
        assertEquals(SignInForm(), viewModel.state.value.form)
    }

    @Test
    fun `revokes the stored token before it forgets the account`() {
        fillIn()
        viewModel.signIn()
        val stored = accounts.account.value

        viewModel.signOut()

        assertEquals(listOf(stored), revoked)
        assertEquals(listOf(stored), accountWhenRevoking)
        assertNull(accounts.account.value)
    }

    @Test
    fun `ignores a second tap while signing out`() {
        fillIn()
        viewModel.signIn()
        val pending = CompletableDeferred<Unit>()
        viewModel = createViewModel(revoker = { account ->
            revoked += account
            pending.await()
        })

        viewModel.signOut()
        viewModel.signOut()
        pending.complete(Unit)

        assertEquals(1, revoked.size)
        assertNull(accounts.account.value)
    }

    @Test
    fun `opens the account store and the session away from the calling thread`() {
        val opened = AtomicInteger()
        val io = StandardTestDispatcher()

        val unresolved = MusicAssistantSettingsViewModel(
            accounts = { opened.incrementAndGet().let { accounts } },
            signer = { _, _, _ -> result },
            session = { opened.incrementAndGet().let { session } },
            revoker = { },
            io = io,
        )

        assertEquals(0, opened.get())
        assertFalse(unresolved.state.value.ready)
        io.scheduler.advanceUntilIdle()
        assertEquals(2, opened.get())
        assertTrue(unresolved.state.value.ready)
        assertEquals(MaSessionState.Disconnected, unresolved.state.value.connection)
    }

    @Test
    fun `signs out even when the revoke blows up`() {
        fillIn()
        viewModel.signIn()
        viewModel = createViewModel(revoker = { error("boom") })

        viewModel.signOut()

        assertNull(accounts.account.value)
        assertFalse(viewModel.state.value.signingOut)
        assertEquals(SignInForm(), viewModel.state.value.form)
    }

    @Test
    fun `signs out even when the revoke is cancelled`() {
        fillIn()
        viewModel.signIn()
        viewModel = createViewModel(revoker = { throw CancellationException("cancelled") })

        viewModel.signOut()

        assertNull(accounts.account.value)
        assertFalse(viewModel.state.value.signingOut)
    }

    @Test
    fun `finishes signing out after the screen is gone`() {
        fillIn()
        viewModel.signIn()
        val pending = CompletableDeferred<Unit>()
        viewModel = createViewModel(revoker = { pending.await() })

        viewModel.signOut()
        viewModel.viewModelScope.cancel()
        pending.complete(Unit)

        assertNull(accounts.account.value)
    }

    @Test
    fun `keeps the sign-in form idle while signing out`() {
        fillIn()
        viewModel.signIn()
        val pending = CompletableDeferred<Unit>()
        viewModel = createViewModel(revoker = { pending.await() })

        viewModel.signOut()

        assertTrue(viewModel.state.value.signingOut)
        assertFalse(viewModel.state.value.form.busy)
        pending.complete(Unit)
        assertFalse(viewModel.state.value.signingOut)
    }

    @Test
    fun `is busy only while signing in`() {
        val pending = CompletableDeferred<MaSignInResult>()
        viewModel = createViewModel { _, _, _ -> pending.await() }
        fillIn()

        viewModel.signIn()

        assertTrue(viewModel.state.value.form.busy)
        assertFalse(viewModel.state.value.signingOut)
        pending.complete(MaSignInResult.Rejected("no"))
        assertFalse(viewModel.state.value.form.busy)
    }

    @Test
    fun `keeps the account when the screen closes right after the token arrives`() {
        val io = StandardTestDispatcher()
        viewModel = createViewModel(io = io)
        io.scheduler.advanceUntilIdle()
        fillIn()

        viewModel.signIn()
        viewModel.viewModelScope.cancel()
        io.scheduler.advanceUntilIdle()

        assertEquals("long.lived.jwt", accounts.account.value?.token)
    }

    @Test
    fun `turns an unexpected sign-in failure into an error`() {
        viewModel = createViewModel { _, _, _ -> error("boom") }
        fillIn()

        viewModel.signIn()

        assertEquals(SignInError.Failed, viewModel.state.value.form.error)
        assertFalse(viewModel.state.value.form.busy)
    }

    @Test
    fun `reports an account that cannot be saved`() {
        val broken = object : MaAccountStore {
            override val account = MutableStateFlow<MaAccount?>(null)

            override fun save(account: MaAccount?) = error("disk full")
        }
        viewModel = MusicAssistantSettingsViewModel({ broken }, { _, _, _ -> result }, { session }, { }, UnconfinedTestDispatcher())
        fillIn()

        viewModel.signIn()

        assertEquals(SignInError.Failed, viewModel.state.value.form.error)
        assertFalse(viewModel.state.value.form.busy)
    }

    @Test
    fun `ignores a provider that was not offered`() {
        val other = GPODDER.copy(name = "gPodder (old server)", instanceId = "gpodder--Q2w3E4r5")
        result = MaSignInResult.SignedIn("long.lived.jwt", listOf(GPODDER, other))
        fillIn()
        viewModel.signIn()

        viewModel.chooseProvider("gpodder--unknown")

        assertNull(accounts.account.value?.gpodderInstanceId)
        assertEquals(listOf(GPODDER, other), viewModel.state.value.form.providerChoices)
    }

    @Test
    fun `ignores a provider choice when signed out`() {
        viewModel.chooseProvider("gpodder--Xk3dTq9a")

        assertNull(accounts.account.value)
    }

    @Test
    fun `keeps the choices when the choice cannot be saved`() {
        val other = GPODDER.copy(name = "gPodder (old server)", instanceId = "gpodder--Q2w3E4r5")
        result = MaSignInResult.SignedIn("long.lived.jwt", listOf(GPODDER, other))
        var failing = false
        val flaky = object : MaAccountStore {
            override val account = MutableStateFlow<MaAccount?>(null)

            override fun save(account: MaAccount?) {
                check(!failing) { "disk full" }
                this.account.value = account
            }
        }
        viewModel = MusicAssistantSettingsViewModel({ flaky }, { _, _, _ -> result }, { session }, { }, UnconfinedTestDispatcher())
        fillIn()
        viewModel.signIn()
        failing = true

        viewModel.chooseProvider("gpodder--Q2w3E4r5")

        assertNull(flaky.account.value?.gpodderInstanceId)
        assertEquals(listOf(GPODDER, other), viewModel.state.value.form.providerChoices)
    }

    @Test
    fun `offers a retry when the account store cannot be opened`() {
        var failing = true
        viewModel = MusicAssistantSettingsViewModel({ if (failing) error("keystore") else accounts }, { _, _, _ -> result }, { session }, { }, UnconfinedTestDispatcher())

        assertTrue(viewModel.state.value.loadFailed)
        assertFalse(viewModel.state.value.ready)
        failing = false
        viewModel.retry()

        assertFalse(viewModel.state.value.loadFailed)
        assertTrue(viewModel.state.value.ready)
    }

    @Test
    fun `never shows the password`() {
        fillIn()

        assertFalse(viewModel.state.value.form.toString().contains("hunter2"))
        assertTrue(viewModel.state.value.form.toString().contains("alice"))
    }

    private fun createViewModel(
        io: CoroutineDispatcher = UnconfinedTestDispatcher(),
        revoker: MaTokenRevoker = MaTokenRevoker { account ->
            revoked += account
            accountWhenRevoking += accounts.account.value
        },
        signer: suspend (HttpUrl, String, String) -> MaSignInResult = { _, _, _ -> result },
    ) = MusicAssistantSettingsViewModel(
        accounts = { accounts },
        signer = { server, username, password -> signer(server, username, password) },
        session = { session },
        revoker = revoker,
        io = io,
    )

    private fun fillIn(server: String = "192.168.1.20") {
        viewModel.updateServerUrl(server)
        viewModel.updateUsername(" alice ")
        viewModel.updatePassword("hunter2")
    }

    private companion object {
        val GPODDER = MaProvider("music", "gpodder", "gPodder (Pod Pocket)", "gpodder--Xk3dTq9a")
    }
}
