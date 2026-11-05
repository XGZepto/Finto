package page.zepto.finto.core.model

/**
 * The one filter every ledger read speaks, so a tapped summary figure opens the
 * blotter on exactly the rows that make it up.
 */
data class LedgerFilter(
    val from: String? = null,
    val to: String? = null,
    val months: List<String> = emptyList(),
    val accounts: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val query: String? = null,
    /** Drop card payments, FX conversions and transfers: money changing pockets. */
    val spendingOnly: Boolean = false,
    val includeTransfers: Boolean = false,
    val maxAmount: Long? = null,
)
