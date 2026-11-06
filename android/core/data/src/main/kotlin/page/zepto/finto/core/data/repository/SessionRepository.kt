package page.zepto.finto.core.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import page.zepto.finto.core.data.ApiErrors
import page.zepto.finto.core.data.ApplicationScope
import page.zepto.finto.core.data.suspendRunCatching
import page.zepto.finto.core.model.FintoJson
import page.zepto.finto.core.model.LoginRequest
import page.zepto.finto.core.model.PreferencesPatch
import page.zepto.finto.core.model.User
import page.zepto.finto.core.network.FintoApi
import page.zepto.finto.core.network.NetworkCache
import page.zepto.finto.core.network.PersistentCookieJar
import page.zepto.finto.core.network.ServerEndpoint
import page.zepto.finto.core.network.SessionEvents
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SessionState {
    /** Reading the stored session; the splash screen stays up. */
    data object Restoring : SessionState

    data class SignedOut(val notice: String? = null) : SessionState

    data class SignedIn(val user: User, val server: String) : SessionState
}

interface SessionRepository {
    val state: StateFlow<SessionState>

    /** Picks up the stored session, if the server still honours it. */
    suspend fun restore()

    suspend fun signIn(server: String, identifier: String, password: String): Result<User>

    suspend fun signOut()

    suspend fun setBaseCurrency(currency: String): Result<User>

    /** Saves theme or language to the account, as the web's settings do. */
    suspend fun updatePreferences(patch: PreferencesPatch): Result<User> = Result.failure(UnsupportedOperationException())
}

@Singleton
internal class DefaultSessionRepository @Inject constructor(
    private val api: FintoApi,
    private val endpoint: ServerEndpoint,
    private val cookies: PersistentCookieJar,
    private val httpCache: NetworkCache,
    private val settings: SettingsRepository,
    events: SessionEvents,
    @ApplicationScope scope: CoroutineScope,
) : SessionRepository {

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Restoring)
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    init {
        scope.launch {
            events.sessionExpiry.collect {
                if (mutableState.value is SessionState.SignedIn) {
                    forget()
                    mutableState.value = SessionState.SignedOut("Your session ended. Sign in again.")
                }
            }
        }
    }

    override suspend fun restore() {
        val saved = settings.current()
        // The server is fixed at build time; a session saved against any other is not resumed.
        val server = ServerEndpoint.normalize(ServerEndpoint.DEFAULT_SERVER)!!
        endpoint.set(server)
        if (!cookies.hasCookieFor(server)) {
            mutableState.value = SessionState.SignedOut()
            return
        }
        val user = suspendRunCatching { api.me() }
        mutableState.value = user.fold(
            onSuccess = { me ->
                settings.setCachedUser(FintoJson.encodeToString(User.serializer(), me))
                SessionState.SignedIn(me, server.toString())
            },
            onFailure = { error ->
                val cached = saved.cachedUserJson?.let {
                    runCatching { FintoJson.decodeFromString(User.serializer(), it) }.getOrNull()
                }
                when {
                    error is HttpException && error.code() == 401 -> {
                        forget()
                        SessionState.SignedOut()
                    }
                    // Offline: open on the last known user; screens show their own errors.
                    cached != null -> SessionState.SignedIn(cached, server.toString())
                    else -> SessionState.SignedOut(ApiErrors.message(error))
                }
            },
        )
    }

    override suspend fun signIn(server: String, identifier: String, password: String): Result<User> {
        val url = ServerEndpoint.normalize(server)
            ?: return Result.failure(IllegalArgumentException("Enter a server address."))
        endpoint.set(url)
        return suspendRunCatching { api.login(LoginRequest(identifier.trim(), password)).user }
            .onSuccess { user ->
                settings.setServer(url.toString())
                settings.setLastIdentifier(identifier.trim())
                settings.setCachedUser(FintoJson.encodeToString(User.serializer(), user))
                adoptTheme(user)
                mutableState.value = SessionState.SignedIn(user, url.toString())
            }
    }

    override suspend fun signOut() {
        // Revoke on the server when reachable; forget locally regardless.
        suspendRunCatching { api.logout() }
        forget()
        mutableState.value = SessionState.SignedOut()
    }

    override suspend fun setBaseCurrency(currency: String): Result<User> =
        suspendRunCatching { api.updatePreferences(PreferencesPatch(baseCurrency = currency)) }
            .onSuccess { user ->
                // Converted figures are cached per currency; drop them with the old choice.
                httpCache.clear()
                settings.setCachedUser(FintoJson.encodeToString(User.serializer(), user))
                val current = mutableState.value
                if (current is SessionState.SignedIn) mutableState.value = current.copy(user = user)
            }

    override suspend fun updatePreferences(patch: PreferencesPatch): Result<User> =
        suspendRunCatching { api.updatePreferences(patch) }
            .onSuccess { user ->
                settings.setCachedUser(FintoJson.encodeToString(User.serializer(), user))
                val current = mutableState.value
                if (current is SessionState.SignedIn) mutableState.value = current.copy(user = user)
            }

    /** The account's theme wins on sign-in, so the app looks the way the web does. */
    private suspend fun adoptTheme(user: User) {
        val mode = when (user.preferences.theme) {
            "dark" -> ThemeMode.Dark
            "light" -> ThemeMode.Light
            "system" -> ThemeMode.System
            else -> null
        }
        if (mode != null) settings.setThemeMode(mode)
    }

    private suspend fun forget() {
        cookies.clear()
        httpCache.clear()
        settings.setCachedUser(null)
    }
}

/** The reporting currency every converted figure uses. */
val User.baseCurrency: String
    get() = preferences.baseCurrency?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: DEFAULT_BASE_CURRENCY

const val DEFAULT_BASE_CURRENCY = "HKD"
