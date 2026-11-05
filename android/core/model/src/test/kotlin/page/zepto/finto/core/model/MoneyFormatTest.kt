package page.zepto.finto.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MoneyFormatTest {
    private val us = Locale.US

    @Test
    fun minorUnitsFollowTheCurrency() {
        assertEquals("HK$1,234.56", MoneyFormat.format(Money(123456, "HKD"), us))
        assertEquals("¥1,234", MoneyFormat.format(Money(1234, "JPY"), us))
    }

    @Test
    fun signs() {
        assertEquals("-$12.00", MoneyFormat.format(Money(-1200, "USD"), us))
        assertEquals("+$12.00", MoneyFormat.format(Money(1200, "USD"), us, signed = true))
        assertEquals("$12.00", MoneyFormat.format(Money(-1200, "USD"), us, absolute = true))
    }

    @Test
    fun wholeUnitsRoundForHeadlines() {
        assertEquals("HK$12,346", MoneyFormat.formatWhole(Money(1234560, "HKD"), us))
    }

    @Test
    fun unknownCurrencyStillFormats() {
        assertEquals(2, MoneyFormat.fractionDigits("XYZ"))
    }
}
