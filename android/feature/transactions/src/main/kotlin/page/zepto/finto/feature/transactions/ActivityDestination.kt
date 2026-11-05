package page.zepto.finto.feature.transactions

import kotlinx.serialization.Serializable

/**
 * The blotter, optionally scoped. Summary opens it on a category and period,
 * Accounts on an account, with the same filter that produced the tapped figure.
 */
@Serializable
data class ActivityDestination(
    val accountId: String? = null,
    val accountName: String? = null,
    val category: String? = null,
    val categoryLabel: String? = null,
    val from: String? = null,
    val spendingOnly: Boolean = false,
) {
    val isScoped: Boolean get() = accountId != null || category != null
}
