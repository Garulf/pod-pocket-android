package au.com.shiftyjelly.pocketcasts.musicassistant.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccount
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaSignInResult
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaSigner
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaTokenRevoker
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class SignInForm(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: SignInError? = null,
    val providerChoices: List<MaProvider> = emptyList(),
) {
    override fun toString() = "SignInForm(serverUrl=$serverUrl, username=$username, password=<redacted>, busy=$busy, error=$error, providerChoices=$providerChoices)"
}

sealed interface SignInError {
    data object InvalidAddress : SignInError

    data class Rejected(val reason: String) : SignInError

    data class Unreachable(val reason: String) : SignInError
}

data class MusicAssistantSettingsState(
    val form: SignInForm,
    val account: MaAccount?,
    val connection: MaSessionState,
    val ready: Boolean = true,
)

@HiltViewModel
class MusicAssistantSettingsViewModel internal constructor(
    private val accounts: Lazy<MaAccountStore>,
    private val signer: MaSigner,
    private val session: Lazy<MaSession>,
    private val revoker: MaTokenRevoker,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        accounts: Lazy<MaAccountStore>,
        signer: MaSigner,
        session: Lazy<MaSession>,
        revoker: MaTokenRevoker,
    ) : this(accounts, signer, session, revoker, Dispatchers.IO)

    private class Services(val accounts: MaAccountStore, val session: MaSession)

    private val form = MutableStateFlow(SignInForm())

    private val services = viewModelScope.async(io) {
        try {
            Services(accounts.get(), session.get())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e("Music Assistant settings could not open: ${e.javaClass.simpleName}")
            null
        }
    }

    val state: StateFlow<MusicAssistantSettingsState> = flow {
        val ready = services.await() ?: return@flow
        emitAll(combine(form, ready.accounts.account, ready.session.state) { form, account, connection -> MusicAssistantSettingsState(form, account, connection) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MusicAssistantSettingsState(form.value, account = null, connection = MaSessionState.Disconnected, ready = false))

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
            val store = services.await()?.accounts
            if (store == null) {
                form.update { it.copy(busy = false) }
                return@launch
            }
            when (val result = signer.signIn(server, current.username.trim(), current.password)) {
                is MaSignInResult.SignedIn -> {
                    save(store, MaAccount(server, result.token, result.gpodderProviders.singleOrNull()?.instanceId))
                    form.value = SignInForm(providerChoices = result.gpodderProviders.takeIf { it.size > 1 }.orEmpty())
                }

                is MaSignInResult.Rejected -> form.update { it.copy(busy = false, error = SignInError.Rejected(result.reason)) }

                is MaSignInResult.Unreachable -> form.update { it.copy(busy = false, error = SignInError.Unreachable(result.reason)) }
            }
        }
    }

    fun chooseProvider(instanceId: String) {
        viewModelScope.launch {
            val store = services.await()?.accounts ?: return@launch
            val account = store.account.value ?: return@launch
            save(store, account.copy(gpodderInstanceId = instanceId))
            form.update { it.copy(providerChoices = emptyList()) }
        }
    }

    fun signOut() {
        if (form.value.busy) {
            return
        }
        form.update { it.copy(busy = true) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                val store = services.await()?.accounts
                if (store == null) {
                    form.update { it.copy(busy = false) }
                    return@withContext
                }
                store.account.value?.let { revoker.revoke(it) }
                save(store, null)
                form.value = SignInForm()
            }
        }
    }

    private suspend fun save(store: MaAccountStore, account: MaAccount?) {
        withContext(io) { store.save(account) }
    }
}
