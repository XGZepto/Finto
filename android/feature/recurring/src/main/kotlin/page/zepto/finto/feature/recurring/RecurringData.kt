package page.zepto.finto.feature.recurring

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.scan
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.model.InstallmentPlan
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Txn

/** Tagged charges and running plans, with what failed to load. */
internal data class RecurringData(
    val charges: List<Txn> = emptyList(),
    val plans: List<InstallmentPlan> = emptyList(),
    val chargesFailure: Throwable? = null,
    val plansFailure: Throwable? = null,
) {
    val failure: Throwable? get() = chargesFailure ?: plansFailure
}

/** The last value a read produced, kept when a later read fails. */
private data class Latest<T>(val value: T? = null, val failure: Throwable? = null)

private fun <T> Flow<Result<T>>.keepingLast(): Flow<Latest<T>> =
    scan(Latest<T>()) { last, result -> result.fold({ Latest(it) }, { Latest(last.value, it) }) }.drop(1)

/** One page per marker tag; the web reads 500 rows, newest first. */
internal const val RECURRING_PAGE = 500

/**
 * Charges tagged `subscription` and `recurring`, merged, and the active
 * instalment plans: the cached copy first, then the network copy.
 */
internal fun LedgerRepository.recurringData(currency: String, refresh: Boolean, withPlans: Boolean = true): Flow<RecurringData> {
    val (subscriptions, recurring) = Recurring.TAGS.map { tag ->
        revalidate(refresh) { policy ->
            transactionsPage(LedgerFilter(tags = listOf(tag)), limit = RECURRING_PAGE, convertTo = currency, policy = policy).items
        }.keepingLast()
    }
    val plans = if (withPlans) {
        revalidate(refresh) { policy -> installments(activeOnly = true, policy = policy).plans }
    } else {
        flowOf(Result.success(emptyList()))
    }
    return combine(subscriptions, recurring, plans.keepingLast()) { a, b, c ->
        RecurringData(
            charges = Recurring.merge(listOf(a.value.orEmpty(), b.value.orEmpty())),
            plans = c.value.orEmpty(),
            chargesFailure = a.failure ?: b.failure,
            plansFailure = c.failure,
        )
    }
}
