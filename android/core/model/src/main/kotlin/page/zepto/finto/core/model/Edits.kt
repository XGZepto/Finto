package page.zepto.finto.core.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A field in a PATCH: left alone, cleared with an explicit null, or set. */
sealed interface Patch<out T> {
    data object Keep : Patch<Nothing>
    data object Clear : Patch<Nothing>
    data class Set<T>(val value: T) : Patch<T>
}

/**
 * Changes to one transaction. The server validates category and subcategory
 * as a pair, so changing the category without naming a subcategory clears it.
 */
data class TransactionChanges(
    val category: Patch<String> = Patch.Keep,
    val subcategory: Patch<String> = Patch.Keep,
    val merchant: Patch<String> = Patch.Keep,
    val notes: Patch<String> = Patch.Keep,
    /** `unreviewed`, `confirmed` or `flagged`. */
    val reviewState: Patch<String> = Patch.Keep,
    /** `posted` or `void`. */
    val status: Patch<String> = Patch.Keep,
) {
    fun toJson(): JsonObject = buildJsonObject {
        fun field(name: String, patch: Patch<String>) = when (patch) {
            Patch.Keep -> Unit
            Patch.Clear -> put(name, JsonNull)
            is Patch.Set -> put(name, JsonPrimitive(patch.value))
        }
        field("category", category)
        field("subcategory", if (category is Patch.Set && subcategory == Patch.Keep) Patch.Clear else subcategory)
        field("merchant", merchant)
        field("notes", notes)
        field("review_state", reviewState)
        field("status", status)
    }

    val isEmpty: Boolean get() = toJson().isEmpty()

    companion object {
        /** Accepting a suggestion confirms the row, as the web's swipe-to-categorise does. */
        fun accept(suggestion: CategorySuggestion) = TransactionChanges(
            category = Patch.Set(suggestion.category),
            subcategory = suggestion.subcategory?.let { Patch.Set(it) } ?: Patch.Clear,
            merchant = suggestion.merchant?.let { Patch.Set(it) } ?: Patch.Keep,
            reviewState = Patch.Set("confirmed"),
        )
    }
}

/** Account status changes, as Accounts sends them. */
object AccountChanges {
    fun close(today: String): JsonObject = buildJsonObject {
        put("closed_on", JsonPrimitive(today))
        put("watch_statements", JsonPrimitive(false))
    }

    fun reopen(): JsonObject = buildJsonObject {
        put("closed_on", JsonNull)
        put("watch_statements", JsonPrimitive(true))
    }

    fun watchStatements(watch: Boolean): JsonObject = buildJsonObject {
        put("watch_statements", JsonPrimitive(watch))
    }
}
