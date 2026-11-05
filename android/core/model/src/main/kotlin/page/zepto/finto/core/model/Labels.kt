package page.zepto.finto.core.model

import java.util.Locale

/** Display names for the ledger's machine identifiers. */
object Labels {
    private val acronyms = setOf("hk", "us", "uk", "hsbc", "amex", "fx", "mpf", "atm", "fps")

    /** `credit_card` → `Credit card`, `hsbc_current` → `HSBC current`. */
    fun humanize(value: String): String {
        val cleaned = value.trim().removePrefix("(").removeSuffix(")")
        if (cleaned.isEmpty()) return value
        return cleaned.split('_', '-', ' ')
            .filter { it.isNotEmpty() }
            .mapIndexed { index, word ->
                val lower = word.lowercase(Locale.ROOT)
                when {
                    lower in acronyms -> lower.uppercase(Locale.ROOT)
                    index == 0 -> lower.replaceFirstChar { it.titlecase(Locale.ROOT) }
                    else -> lower
                }
            }
            .joinToString(" ")
    }

    fun kind(kind: String): String = when (kind) {
        "cc_payment" -> "Card payment"
        "fx_conversion" -> "FX conversion"
        else -> humanize(kind)
    }

    fun accountType(type: String): String = when (type) {
        "credit_card" -> "Credit cards"
        "checking", "current" -> "Current accounts"
        "savings" -> "Savings"
        "investment", "mpf" -> "Investments"
        "multi_currency" -> "Multi-currency"
        else -> humanize(type)
    }
}

/** Dates as people read them, in the device's locale. */
object DisplayDates {
    private val iso = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

    fun parse(value: String?): java.time.LocalDate? =
        value?.takeIf { it.length >= 10 }?.let { runCatching { java.time.LocalDate.parse(it.take(10), iso) }.getOrNull() }

    /** `22 Nov 2026` (or the locale's medium form). */
    fun medium(value: String?): String = parse(value)
        ?.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
        ?: value.orEmpty()

    /** `Saturday, 22 November` for list section headers; the year only when it is not this year. */
    fun header(date: java.time.LocalDate, today: java.time.LocalDate = java.time.LocalDate.now()): String {
        val pattern = if (date.year == today.year) "EEEE, d MMMM" else "EEEE, d MMMM yyyy"
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
        }
    }

    fun monthName(date: java.time.LocalDate): String =
        date.format(java.time.format.DateTimeFormatter.ofPattern("LLLL"))

    /** `Nov 26` for a `2026-11` bucket, where the year matters (descriptions). */
    fun shortMonth(bucket: String): String = runCatching {
        java.time.YearMonth.parse(bucket).format(java.time.format.DateTimeFormatter.ofPattern("MMM yy"))
    }.getOrDefault(bucket)

    /** `Nov` for a chart axis, where the range control already says which year. */
    fun monthAbbreviation(bucket: String): String = runCatching {
        java.time.YearMonth.parse(bucket).format(java.time.format.DateTimeFormatter.ofPattern("MMM"))
    }.getOrDefault(bucket)

    /** `Oct 31` this year, `Oct 31, 2025` otherwise. */
    fun short(value: String?, today: java.time.LocalDate = java.time.LocalDate.now()): String {
        val date = parse(value) ?: return value.orEmpty()
        return if (date.year == today.year) {
            date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        } else {
            date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
        }
    }
}
