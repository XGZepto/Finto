package page.zepto.finto.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import page.zepto.finto.core.network.ServerEndpoint
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { System, Light, Dark }

data class AppSettings(
    val serverUrl: String = ServerEndpoint.DEFAULT_SERVER,
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val lastIdentifier: String = "",
    /** The signed-in user as last seen, so the app opens offline. */
    val cachedUserJson: String? = null,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setServer(url: String)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setLastIdentifier(identifier: String)
    suspend fun setCachedUser(json: String?)
}

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "finto_settings")

@Singleton
internal class DataStoreSettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            serverUrl = prefs[SERVER] ?: ServerEndpoint.DEFAULT_SERVER,
            themeMode = prefs[THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.System,
            dynamicColor = prefs[DYNAMIC_COLOR] ?: true,
            lastIdentifier = prefs[LAST_IDENTIFIER].orEmpty(),
            cachedUserJson = prefs[CACHED_USER],
        )
    }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun setServer(url: String) {
        store.edit { it[SERVER] = url }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setLastIdentifier(identifier: String) {
        store.edit { it[LAST_IDENTIFIER] = identifier }
    }

    override suspend fun setCachedUser(json: String?) {
        store.edit { if (json == null) it.remove(CACHED_USER) else it[CACHED_USER] = json }
    }

    private companion object {
        val SERVER = stringPreferencesKey("server_url")
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LAST_IDENTIFIER = stringPreferencesKey("last_identifier")
        val CACHED_USER = stringPreferencesKey("cached_user")
    }
}
