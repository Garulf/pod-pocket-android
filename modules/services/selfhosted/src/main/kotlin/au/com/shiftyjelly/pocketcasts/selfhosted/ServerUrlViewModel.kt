package au.com.shiftyjelly.pocketcasts.selfhosted

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

@HiltViewModel
class ServerUrlViewModel @Inject constructor(
    private val store: ServerUrlStore,
    private val checker: ServerConnectionChecker,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerUrlState(text = store.serverUrl?.toString().orEmpty()))
    val state: StateFlow<ServerUrlState> = _state
    private var checkJob: Job? = null

    fun updateText(text: String) {
        checkJob?.cancel()
        _state.update { ServerUrlState(text = text) }
    }

    fun hasValidUrl(): Boolean {
        val isValid = ServerUrls.parse(_state.value.text) != null
        if (!isValid) {
            _state.update { it.copy(status = ServerStatus.Invalid) }
        }
        return isValid
    }

    fun checkConnection(onReachable: () -> Unit) {
        if (_state.value.status == ServerStatus.Checking) {
            return
        }
        val url = ServerUrls.parse(_state.value.text)
        if (url == null) {
            _state.update { it.copy(status = ServerStatus.Invalid) }
            return
        }
        _state.update { it.copy(status = ServerStatus.Checking) }
        checkJob = viewModelScope.launch {
            when (val result = runCheck(url)) {
                ConnectionResult.Reachable -> {
                    store.setServerUrl(url)
                    _state.update { it.copy(status = ServerStatus.Reachable(url.host)) }
                    onReachable()
                }

                is ConnectionResult.Unreachable -> {
                    _state.update { it.copy(status = ServerStatus.Unreachable(result.reason)) }
                }
            }
        }
    }

    private suspend fun runCheck(url: HttpUrl): ConnectionResult {
        return try {
            checker.check(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionResult.Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }
}

data class ServerUrlState(
    val text: String,
    val status: ServerStatus = ServerStatus.Unchecked,
)

sealed interface ServerStatus {
    data object Unchecked : ServerStatus

    data object Invalid : ServerStatus

    data object Checking : ServerStatus

    data class Reachable(val host: String) : ServerStatus

    data class Unreachable(val reason: String) : ServerStatus
}
