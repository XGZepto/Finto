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
    val identifier: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** Someone has signed in here before: greet them back. */
    val returning: Boolean = false,
) {
    val canSubmit: Boolean
        get() = identifier.isNotBlank() && password.isNotEmpty() && !submitting
}

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
                it.copy(identifier = saved.lastIdentifier, returning = saved.lastIdentifier.isNotBlank())
            }
        }
    }

    fun onIdentifierChange(value: String) = mutableState.update { it.copy(identifier = value, error = null) }

    fun onPasswordChange(value: String) = mutableState.update { it.copy(password = value, error = null) }

    fun submit() {
        val current = mutableState.value
        if (!current.canSubmit) return
        mutableState.update { it.copy(submitting = true, error = null, notice = null) }
        viewModelScope.launch {
            // The server is fixed when the APK is built (see core/network's build file).
            session.signIn(ServerEndpoint.DEFAULT_SERVER, current.identifier, current.password)
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
}
