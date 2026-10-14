package au.com.shiftyjelly.pocketcasts.musicassistant.settings

import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaSignInResult
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.PodPocketUser
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.eventually
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

@OptIn(ExperimentalCoroutinesApi::class)
class MusicAssistantSettingsProviderChoiceTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer().signedIn()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val accounts = InMemoryAccountStore(account(server.url, gpodder = null))
    private val session = MaSession(scope, accounts, { MaConnection(OkHttpClient()) })

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.reply("providers", "providers")
    }

    @After
    fun tearDown() {
        session.stop()
        scope.cancel()
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `offers the gPodder providers again when the page opens before one was chosen`() {
        session.start()

        val viewModel = createViewModel()

        eventually { viewModel.state.value.form.providerChoices == listOf(GPODDER) }
        assertFalse(viewModel.state.value.form.noProviders)
        viewModel.chooseProvider(GPODDER.instanceId)
        eventually { accounts.account.value?.gpodderInstanceId == GPODDER.instanceId }
        eventually { viewModel.state.value.form.providerChoices.isEmpty() }
    }

    @Test
    fun `waits for the connection before it lists the providers`() {
        val viewModel = createViewModel()
        eventually { viewModel.state.value.ready }
        Thread.sleep(200)
        assertTrue(viewModel.state.value.form.providerChoices.isEmpty())
        assertFalse(viewModel.state.value.form.noProviders)

        session.start()

        eventually { viewModel.state.value.form.providerChoices == listOf(GPODDER) }
    }

    @Test
    fun `says no provider was found only when Music Assistant lists none`() {
        server.replyEdited("providers", "providers") { reply ->
            @Suppress("UNCHECKED_CAST")
            reply["result"] = (reply["result"] as List<Map<String, Any?>>).filter { it["domain"] != "gpodder" }
        }
        session.start()

        val viewModel = createViewModel()

        eventually { viewModel.state.value.form.noProviders }
        assertTrue(viewModel.state.value.form.providerChoices.isEmpty())
    }

    @Test
    fun `does not claim there is no provider when the list cannot be read`() {
        server.reply("providers", "error_invalid_command")
        session.start()

        val viewModel = createViewModel()

        eventually { server.commands.any { it.name == "providers" } }
        Thread.sleep(200)
        assertFalse(viewModel.state.value.form.noProviders)
        assertTrue(viewModel.state.value.form.providerChoices.isEmpty())
    }

    @Test
    fun `asks again for the provider when another Pod Pocket user is signed in`() {
        accounts.save(account(server.url).copy(gpodderOwner = ALICE))
        session.start()

        val viewModel = createViewModel(user = BOB)

        eventually { viewModel.state.value.form.providerChoices == listOf(GPODDER) }
        viewModel.chooseProvider(GPODDER.instanceId)
        eventually { accounts.account.value?.gpodderOwner == BOB }
        assertEquals(GPODDER.instanceId, accounts.account.value?.gpodderInstanceFor(BOB))
    }

    @Test
    fun `keeps the chosen provider for the Pod Pocket user who chose it`() {
        accounts.save(account(server.url).copy(gpodderOwner = ALICE))
        session.start()

        val viewModel = createViewModel(user = ALICE)

        eventually { session.state.value is MaSessionState.Connected }
        Thread.sleep(200)
        assertTrue(viewModel.state.value.form.providerChoices.isEmpty())
        assertTrue(server.commands.none { it.name == "providers" })
    }

    private fun createViewModel(user: String? = null) = MusicAssistantSettingsViewModel(
        accounts = { accounts },
        signer = { _, _, _ -> MaSignInResult.Rejected("not used") },
        session = { session },
        revoker = { },
        io = Dispatchers.IO,
        user = PodPocketUser { user },
    )

    private companion object {
        val GPODDER = MaProvider("music", "gpodder", "gPodder (Pod Pocket)", "gpodder--Xk3dTq9a")
        const val ALICE = "https://pods.example.com/|alice@example.com"
        const val BOB = "https://pods.example.com/|bob@example.com"
    }
}
