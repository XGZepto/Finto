package page.zepto.finto.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * The one JSON configuration for the Finto API.
 *
 * The server speaks snake_case and adds fields over time, so unknown keys are
 * ignored and absent optional fields fall back to their defaults. Nulls are
 * left out of request bodies, which is what makes a PATCH send only the fields
 * it means to change.
 */
@OptIn(ExperimentalSerializationApi::class)
val FintoJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}
