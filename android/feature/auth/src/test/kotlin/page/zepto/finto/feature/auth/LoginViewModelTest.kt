package page.zepto.finto.feature.auth

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import page.zepto.finto.core.network.ServerEndpoint
import page.zepto.finto.core.data.repository.AppSettings
import page.zepto.finto.core.testing.FakeSessionRepository
import page.zepto.finto.core.testing.FakeSettingsRepository
import page.zepto.finto.core.testing.MainDispatcherRule
import retrofit2.HttpException
import retrofit2.Response

class LoginViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val session = FakeSessionRepository()
    private val settings = FakeSettingsRepository(AppSettings(serverUrl = "https://finto.example.com/", lastIdentifier = "owner"))

    @Test
    fun prefillsTheLastUserAndGreetsThemBack() {
        val state = LoginViewModel(session, settings).state.value
        assertEquals("owner", state.identifier)
        assertTrue(state.returning)
        assertFalse(state.canSubmit)
    }

    @Test
    fun wrongPasswordSaysSo() {
        session.signInResult = Result.failure(HttpException(Response.error<Any>(401, "{}".toResponseBody())))
        val viewModel = LoginViewModel(session, settings)
        viewModel.onPasswordChange("nope")
        viewModel.submit()
        assertEquals("Incorrect username, email or password.", viewModel.state.value.error)
        assertFalse(viewModel.state.value.submitting)
    }

    @Test
    fun successClearsThePassword() {
        val viewModel = LoginViewModel(session, settings)
        viewModel.onPasswordChange("secret")
        viewModel.submit()
        // Always the server the APK was built for, whatever an older version saved.
        assertEquals(Triple(ServerEndpoint.DEFAULT_SERVER, "owner", "secret"), session.signIns.single())
        assertEquals("", viewModel.state.value.password)
    }
}
