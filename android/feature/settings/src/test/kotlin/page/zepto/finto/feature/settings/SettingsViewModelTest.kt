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
import page.zepto.finto.core.testing.FakeSessionRepository
import page.zepto.finto.core.testing.FakeSettingsRepository
import page.zepto.finto.core.testing.MainDispatcherRule

class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val session = FakeSessionRepository()
    private val settings = FakeSettingsRepository()
    // Built inside each test, after the rule has installed the test main dispatcher.
    private val viewModel by lazy { SettingsViewModel(session, settings) }

    @Test
    fun reportingCurrencyIsSavedToTheAccount() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.setCurrency("USD")
        assertEquals("USD", (session.state.value as SessionState.SignedIn).user.preferences.baseCurrency)
        assertEquals("USD", viewModel.state.value.currency)
    }

    @Test
    fun themeIsALocalPreference() = runTest {
        viewModel.setThemeMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, settings.settings.first().themeMode)
    }

    @Test
    fun signOutEndsTheSession() = runTest {
        viewModel.signOut()
        assertEquals(SessionState.SignedOut(), session.state.value)
    }
}
