package au.com.shiftyjelly.pocketcasts.musicassistant.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccount
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaSignInResult
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaSigner
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaTokenRevoker
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.PodPocketUser
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.gpodderProvidersIn
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaServerUrls
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaProvider
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import timber.log.Timber

data class SignInForm(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: SignInError? = null,
    val providerChoices: List<MaProvider> = emptyList(),
    val noProviders: Boolean = false,
) {
    override fun toString() = "SignInForm(serverUrl=$serverUrl, username=$username, password=<redacted>, busy=$busy, error=$error, providerChoices=$providerChoices, noProviders=$noProviders)"
}

sealed interface SignInError {
    data object InvalidAddress : SignInError

    data class Rejected(val reason: String) : SignInError

    data class Unreachable(val reason: String) : SignInError

    data object Failed : SignInError
}

data class MusicAssistantSettingsState(
    val form: SignInForm,
    val account: MaAccount?,
    val connection: MaSessionState,
    val ready: Boolean = true,
    val signingOut: Boolean = false,
    val loadFailed: Boolean = false,
)

@HiltViewModel
class MusicAssistantSettingsViewModel internal constructor(
    private val accounts: Lazy<MaAccountStore>,
    private val signer: MaSigner,
    private val session: Lazy<MaSession>,
    private val revoker: MaTokenRevoker,
    private val io: CoroutineDispatcher,
    private val user: PodPocketUser = PodPocketUser { null },
) : ViewModel() {
    @Inject
    constructor(
        accounts: Lazy<MaAccountStore>,
        signer: MaSigner,
        session: Lazy<MaSession>,
        revoker: MaTokenRevoker,
        user: PodPocketUser,
    ) : this(accounts, signer, session, revoker, Dispatchers.IO, user)

    private class Services(val accounts: MaAccountStore, val session: MaSession, val user: String?)

    private sealed interface Load {
        data object Opening : Load

        data object Failed : Load

        class Ready(val services: Services) : Load
    }

    private val form = MutableStateFlow(SignInForm())
    private val signingOut = MutableStateFlow(false)
    private val load = MutableStateFlow<Load>(Load.Opening)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<MusicAssistantSettingsState> = load.flatMapLatest { loaded ->
        when (loaded) {
            is Load.Ready -> combine(form, signingOut, loaded.services.accounts.account, loaded.services.session.state) { form, signingOut, account, connection ->
                MusicAssistantSettingsState(form, account, connection, signingOut = signingOut)
            }

            Load.Opening, Load.Failed -> form.map { MusicAssistantSettingsState(it, null, MaSessionState.Disconnected, ready = false, loadFailed = loaded == Load.Failed) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MusicAssistantSettingsState(form.value, account = null, connection = MaSessionState.Disconnected, ready = false))

    init {
        open()
    }

    fun retry() {
        if (load.value == Load.Failed) {
            open()
        }
    }

    fun updateServerUrl(text: String) = form.update { it.copy(serverUrl = text, error = null) }

    fun updateUsername(text: String) = form.update { it.copy(username = text, error = null) }

    fun updatePassword(text: String) = form.update { it.copy(password = text, error = null) }

    fun signIn() {
        val current = form.value
        if (current.busy) {
            return
        }
        val server = MaServerUrls.parse(current.serverUrl)
        if (server == null) {
            form.update { it.copy(error = SignInError.InvalidAddress) }
            return
        }
        form.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val services = services()
            if (services == null) {
                failSignIn(SignInError.Failed)
                return@launch
            }
            val store = services.accounts
            when (val result = attempt(server, current)) {
                is MaSignInResult.SignedIn -> {
                    val account = MaAccount(server, result.token, result.gpodderProviders.singleOrNull()?.instanceId, services.user)
                    if (withContext(NonCancellable) { save(store, account) }) {
                        form.value = SignInForm(
                            providerChoices = result.gpodderProviders.takeIf { it.size > 1 }.orEmpty(),
                            noProviders = result.gpodderProviders.isEmpty(),
                        )
                    } else {
                        failSignIn(SignInError.Failed)
                    }
                }

                is MaSignInResult.Rejected -> failSignIn(SignInError.Rejected(result.reason))

                is MaSignInResult.Unreachable -> failSignIn(SignInError.Unreachable(result.reason))

                null -> failSignIn(SignInError.Failed)
            }
        }
    }

    fun chooseProvider(instanceId: String) {
        if (form.value.providerChoices.none { it.instanceId == instanceId }) {
            return
        }
        viewModelScope.launch {
            val services = services() ?: return@launch
            val store = services.accounts
            val account = store.account.value ?: return@launch
            if (save(store, account.copy(gpodderInstanceId = instanceId, gpodderOwner = services.user))) {
                form.update { it.copy(providerChoices = emptyList()) }
            }
        }
    }

    fun signOut() {
        if (signingOut.value) {
            return
        }
        signingOut.value = true
        viewModelScope.launch {
            withContext(NonCancellable) {
                val store = services()?.accounts
                try {
                    store?.account?.value?.let { revoker.revoke(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("Music Assistant sign-out could not revoke the token: ${e.javaClass.simpleName}")
                } finally {
                    store?.let { save(it, null) }
                    form.value = SignInForm()
                    signingOut.value = false
                }
            }
        }
    }

    private fun open() {
        load.value = Load.Opening
        viewModelScope.launch {
            val loaded = try {
                withContext(io) { Load.Ready(Services(accounts.get(), session.get(), user.key())) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("Music Assistant settings could not open: ${e.javaClass.simpleName}")
                Load.Failed
            }
            load.value = loaded
            if (loaded is Load.Ready) {
                offerProvidersWhileNoneIsChosen(loaded.services)
            }
        }
    }

    private suspend fun offerProvidersWhileNoneIsChosen(services: Services) {
        combine(services.accounts.account, services.session.state) { account, connection ->
            account?.takeIf { it.gpodderInstanceFor(services.user) == null && connection is MaSessionState.Connected }
        }.distinctUntilChanged().collectLatest { unchosen ->
            if (unchosen != null && form.value.providerChoices.isEmpty()) {
                offerProviders(services.session)
            }
        }
    }

    private suspend fun offerProviders(session: MaSession) {
        val providers = try {
            withContext(io) { gpodderProvidersIn(session.call("providers")) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Music Assistant providers could not be listed: ${e.javaClass.simpleName}")
            return
        }
        form.update { it.copy(providerChoices = providers, noProviders = providers.isEmpty()) }
    }

    private suspend fun services(): Services? = (load.first { it !is Load.Opening } as? Load.Ready)?.services

    private suspend fun attempt(server: HttpUrl, current: SignInForm): MaSignInResult? {
        return try {
            signer.signIn(server, current.username.trim(), current.password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e("Music Assistant sign-in failed unexpectedly: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun failSignIn(error: SignInError) = form.update { it.copy(busy = false, error = error) }

    private suspend fun save(store: MaAccountStore, account: MaAccount?): Boolean {
        return try {
            withContext(io) { store.save(account) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e("Music Assistant account could not be saved: ${e.javaClass.simpleName}")
            false
        }
    }
}
