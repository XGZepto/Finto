package page.zepto.finto.feature.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.data.repository.SessionState
import page.zepto.finto.core.data.repository.ThemeMode
import page.zepto.finto.core.model.PreferencesPatch
import page.zepto.finto.core.model.Translations
import page.zepto.finto.core.testing.FakeAdminRepository
import page.zepto.finto.core.testing.FakeSessionRepository
import page.zepto.finto.core.testing.FakeSettingsRepository
import page.zepto.finto.core.testing.MainDispatcherRule

class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val session = FakeSessionRepository()
    private val settings = FakeSettingsRepository()
    // Built inside each test, after the rule has installed the test main dispatcher.
    private val admin = FakeAdminRepository()
    private val viewModel by lazy { SettingsViewModel(session, settings, admin) }

    @Test
    fun reportingCurrencyIsSavedToTheAccount() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.setCurrency("USD")
        assertEquals("USD", (session.state.value as SessionState.SignedIn).user.preferences.baseCurrency)
        assertEquals("USD", viewModel.state.value.currency)
    }

    @Test
    fun themeAppliesHereAndIsSavedToTheAccount() = runTest {
        viewModel.setThemeMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, settings.settings.first().themeMode)
        assertEquals(PreferencesPatch(theme = "dark"), session.preferencePatches.single())
    }

    @Test
    fun languageIsSavedToTheAccount() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.setLanguage(Translations.TRADITIONAL_CHINESE)
        assertEquals("zh-Hant", viewModel.state.value.language)
        assertEquals("設定", Translations.text("Settings", "zh-Hant"))
        assertEquals("Import", Translations.text("Import", "zh-Hant"))
    }

    @Test
    fun aNewKeyIsShownOnceAndRevokingRemovesIt() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.createApiKey()
        assertEquals("fk_test_secret", viewModel.state.value.newKey)
        assertEquals(listOf("Agent access"), viewModel.state.value.apiKeys.map { it.name })
        assertEquals(null, viewModel.state.value.apiKeys.single().key)
        viewModel.dismissNewKey()
        assertEquals(null, viewModel.state.value.newKey)
        viewModel.revokeApiKey("key-1")
        assertEquals(emptyList<String>(), viewModel.state.value.apiKeys.map { it.id })
        assertEquals(emptyList<String>(), admin.keys.map { it.id })
    }

    @Test
    fun signOutEndsTheSession() = runTest {
        viewModel.signOut()
        assertEquals(SessionState.SignedOut(), session.state.value)
    }
}
