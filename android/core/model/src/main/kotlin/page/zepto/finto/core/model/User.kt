package page.zepto.finto.core.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val username: String,
    val email: String,
    val preferences: UserPreferences = UserPreferences(),
)

@Serializable
data class UserPreferences(
    val theme: String? = null,
    val language: String? = null,
    val baseCurrency: String? = null,
)

@Serializable
data class LoginRequest(val identifier: String, val password: String)

@Serializable
data class LoginResponse(val ok: Boolean, val user: User)

@Serializable
data class OkResponse(val ok: Boolean = true)

/** Only the fields that are set are sent, so this changes nothing else. */
@Serializable
data class PreferencesPatch(
    val baseCurrency: String? = null,
    val theme: String? = null,
)
