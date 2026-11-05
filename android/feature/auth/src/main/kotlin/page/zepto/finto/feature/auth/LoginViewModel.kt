package page.zepto.finto.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.repository.SessionRepository
import page.zepto.finto.core.data.repository.SettingsRepository
import page.zepto.finto.core.network.ServerEndpoint
import retrofit2.HttpException
import javax.inject.Inject

data class LoginUiState(
    val server: String = displayServer(ServerEndpoint.DEFAULT_SERVER),
    val identifier: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val canSubmit: Boolean
        get() = server.isNotBlank() && identifier.isNotBlank() && password.isNotEmpty() && !submitting
}

/** `https://finto.zepto.page/` → `finto.zepto.page`, the way people type it. */
internal fun displayServer(url: String): String =
    url.removePrefix("https://").trimEnd('/')

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val session: SessionRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = settings.current()
            mutableState.update {
                it.copy(server = displayServer(saved.serverUrl), identifier = saved.lastIdentifier)
            }
        }
    }

    fun onServerChange(value: String) = mutableState.update { it.copy(server = value, error = null) }

    fun onIdentifierChange(value: String) = mutableState.update { it.copy(identifier = value, error = null) }

    fun onPasswordChange(value: String) = mutableState.update { it.copy(password = value, error = null) }

    fun submit() {
        val current = mutableState.value
        if (!current.canSubmit) return
        mutableState.update { it.copy(submitting = true, error = null, notice = null) }
        viewModelScope.launch {
            session.signIn(current.server, current.identifier, current.password)
                .onFailure { error ->
                    val message = if (error is HttpException && error.code() == 401) {
                        "Incorrect username, email or password."
                    } else {
                        ApiErrors.message(error)
                    }
                    mutableState.update { it.copy(submitting = false, error = message) }
                }
                .onSuccess {
                    // The session state changes and the app leaves this screen; clear
                    // the password so it is not still there after a later sign-out.
                    mutableState.update { it.copy(submitting = false, password = "") }
                }
        }
    }

    /** The public demo: invented data, read-only, published credentials. */
    fun useDemo() {
        mutableState.update {
            it.copy(
                server = displayServer(ServerEndpoint.DEMO_SERVER),
                identifier = DEMO_USER,
                password = DEMO_PASSWORD,
                error = null,
            )
        }
        submit()
    }

    private companion object {
        const val DEMO_USER = "demo"
        const val DEMO_PASSWORD = "finto-demo-2026"
    }
}
