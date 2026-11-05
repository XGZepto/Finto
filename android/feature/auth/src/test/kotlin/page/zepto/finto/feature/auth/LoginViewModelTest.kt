package page.zepto.finto.feature.auth

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
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
    fun prefillsTheLastServerAndUser() {
        val state = LoginViewModel(session, settings).state.value
        assertEquals("finto.example.com", state.server)
        assertEquals("owner", state.identifier)
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
        assertEquals(Triple("finto.example.com", "owner", "secret"), session.signIns.single())
        assertEquals("", viewModel.state.value.password)
    }

    @Test
    fun demoUsesThePublicDemoServer() {
        val viewModel = LoginViewModel(session, settings)
        viewModel.useDemo()
        val (server, user, _) = session.signIns.single()
        assertEquals("demo.finto.zepto.page", server)
        assertEquals("demo", user)
        assertTrue(viewModel.state.value.error == null)
    }
}
