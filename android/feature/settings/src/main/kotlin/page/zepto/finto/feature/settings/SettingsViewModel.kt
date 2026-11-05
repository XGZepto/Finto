package page.zepto.finto.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.repository.SessionRepository
import page.zepto.finto.core.data.repository.SessionState
import page.zepto.finto.core.data.repository.SettingsRepository
import page.zepto.finto.core.data.repository.ThemeMode
import page.zepto.finto.core.data.repository.baseCurrency
import javax.inject.Inject

data class SettingsUiState(
    val username: String = "",
    val email: String = "",
    val server: String = "",
    val currency: String = "HKD",
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val savingCurrency: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val session: SessionRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> = combine(session.state, settings.settings, transient) { session, app, local ->
        val signedIn = session as? SessionState.SignedIn
        local.copy(
            username = signedIn?.user?.username.orEmpty(),
            email = signedIn?.user?.email.orEmpty(),
            server = signedIn?.server?.removePrefix("https://")?.trimEnd('/').orEmpty(),
            currency = signedIn?.user?.baseCurrency ?: local.currency,
            themeMode = app.themeMode,
            dynamicColor = app.dynamicColor,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setCurrency(currency: String) {
        transient.update { it.copy(savingCurrency = true, error = null) }
        viewModelScope.launch {
            session.setBaseCurrency(currency)
                .onFailure { error -> transient.update { it.copy(error = ApiErrors.message(error)) } }
            transient.update { it.copy(savingCurrency = false) }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }

    companion object {
        /** The reporting currencies the web app offers. */
        val CURRENCIES = listOf("USD", "HKD", "GBP", "EUR", "JPY", "CNY", "SGD", "AUD", "CAD")
    }
}
