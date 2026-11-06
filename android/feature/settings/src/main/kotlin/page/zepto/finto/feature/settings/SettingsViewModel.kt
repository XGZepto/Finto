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
import page.zepto.finto.core.data.repository.AdminRepository
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.ApiKey
import page.zepto.finto.core.model.PreferencesPatch
import page.zepto.finto.core.model.ReportingCurrencies
import page.zepto.finto.core.model.Translations
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
    val language: String = Translations.ENGLISH,
    val savingCurrency: Boolean = false,
    val error: String? = null,
    val apiKeys: List<ApiKey> = emptyList(),
    val apiKeysLoading: Boolean = true,
    /** A key just created: its secret is shown once, then never again. */
    val newKey: String? = null,
    val apiError: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val session: SessionRepository,
    private val settings: SettingsRepository,
    private val admin: AdminRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> = combine(session.state, settings.settings, transient) { session, app, local ->
        val signedIn = session as? SessionState.SignedIn
        local.copy(
            username = signedIn?.user?.username.orEmpty(),
            email = signedIn?.user?.email.orEmpty(),
            server = signedIn?.server?.removePrefix("https://")?.trimEnd('/').orEmpty(),
            currency = signedIn?.user?.baseCurrency ?: local.currency,
            language = signedIn?.user?.preferences?.language ?: Translations.ENGLISH,
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

    init {
        loadKeys()
    }

    /** Applies at once on this phone and saves to the account, so the web follows. */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settings.setThemeMode(mode)
            session.updatePreferences(PreferencesPatch(theme = mode.name.lowercase()))
        }
    }

    fun setLanguage(language: String) {
        viewModelScope.launch {
            session.updatePreferences(PreferencesPatch(language = language))
                .onFailure { error -> transient.update { it.copy(error = ApiErrors.message(error)) } }
        }
    }

    private fun loadKeys() {
        viewModelScope.launch {
            suspendRunCatching { admin.apiKeys() }
                .onSuccess { keys -> transient.update { it.copy(apiKeys = keys, apiKeysLoading = false) } }
                .onFailure { error -> transient.update { it.copy(apiKeysLoading = false, apiError = ApiErrors.message(error)) } }
        }
    }

    fun createApiKey() {
        transient.update { it.copy(apiError = null) }
        viewModelScope.launch {
            suspendRunCatching { admin.createApiKey(API_KEY_NAME) }
                .onSuccess { key ->
                    transient.update { state -> state.copy(newKey = key.key, apiKeys = listOf(key.copy(key = null)) + state.apiKeys) }
                }
                .onFailure { error -> transient.update { it.copy(apiError = ApiErrors.message(error)) } }
        }
    }

    fun dismissNewKey() {
        transient.update { it.copy(newKey = null) }
    }

    fun revokeApiKey(id: String) {
        val before = transient.value.apiKeys
        transient.update { state -> state.copy(apiKeys = state.apiKeys.filterNot { it.id == id }, apiError = null) }
        viewModelScope.launch {
            suspendRunCatching { admin.revokeApiKey(id) }
                .onFailure { error -> transient.update { it.copy(apiKeys = before, apiError = ApiErrors.message(error)) } }
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }

    companion object {
        /** The reporting currencies the web app offers. */
        val CURRENCIES = ReportingCurrencies

        /** The name the web gives keys it generates. */
        const val API_KEY_NAME = "Agent access"
    }
}
