package page.zepto.finto.feature.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import page.zepto.finto.core.model.LedgerFilter
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.ReviewCandidate
import page.zepto.finto.core.model.ReviewQueue
import java.util.Locale

class ReviewLogicTest {

    @Test
    fun scoresFallIntoTheWebBands() {
        assertEquals(ScoreBand.High, ReviewLogic.band(0.85))
        assertEquals(ScoreBand.High, ReviewLogic.band(0.99))
        assertEquals(ScoreBand.Medium, ReviewLogic.band(0.7))
        assertEquals(ScoreBand.Medium, ReviewLogic.band(0.849))
        assertEquals(ScoreBand.Low, ReviewLogic.band(0.69))
        assertEquals(93, ReviewLogic.percent(0.927))
    }

    @Test
    fun duplicatesPairTheRowToKeepWithTheOneToDrop() {
        val item = ReviewCandidate(
            id = "d1", keepId = "a", keepDate = "2026-10-14", keepAccount = "amex", keepDesc = "CITYSUPER", keepAmount = -100, keepCurrency = "HKD",
            dupeId = "b", dupeDate = "2026-10-15", dupeAccount = "amex", dupeDesc = "CITYSUPER HK", dupeAmount = -100, dupeCurrency = "HKD",
        )
        val sides = ReviewLogic.sides(ReviewQueue.Duplicates, item)
        assertEquals(listOf("Keep", "Drop"), sides.map { it.role })
        assertEquals(listOf("a", "b"), sides.map { it.id })
        assertEquals(Money(-100, "HKD"), sides.last().amount)
        assertEquals("CITYSUPER HK", sides.last().description)
    }

    @Test
    fun transfersPairOutWithInAndInstalmentsHaveNoPair() {
        val item = ReviewCandidate(id = "t1", outId = "o", outAccount = "hsbc", inId = "i", inAccount = "savings", outAmount = -5, inAmount = 5)
        assertEquals(listOf("Out", "In"), ReviewLogic.sides(ReviewQueue.Transfers, item).map { it.role })
        assertTrue(ReviewLogic.sides(ReviewQueue.Installments, item).isEmpty())
    }

    @Test
    fun gapNamesDaysAndAmountOnlyWhenRecorded() {
        Locale.setDefault(Locale.UK)
        assertNull(ReviewLogic.gap(ReviewCandidate(id = "d")))
        assertEquals("0 day gap", ReviewLogic.gap(ReviewCandidate(id = "t", dateDelta = 0, amountDelta = 0)))
        assertEquals(
            "2 day gap · HK$15.00 amount gap",
            ReviewLogic.gap(ReviewCandidate(id = "t", dateDelta = 2, amountDelta = -1_500, outCurrency = "HKD")),
        )
        assertEquals("1 day gap · 50 amount gap", ReviewLogic.gap(ReviewCandidate(id = "t", dateDelta = 1, amountDelta = 50)))
    }

    @Test
    fun consequenceIsWordedPerQueue() {
        assertEquals("Hides the second row as a duplicate. Reversible.", ReviewLogic.consequence(ReviewQueue.Duplicates))
        assertEquals("Links both legs into one transfer.", ReviewLogic.consequence(ReviewQueue.Transfers))
        assertEquals("Groups these charges into one instalment plan.", ReviewLogic.consequence(ReviewQueue.Installments))
    }

    @Test
    fun blotterOpensOnTheRowsDayWithEverythingIncluded() {
        assertEquals(
            LedgerFilter(accounts = listOf("amex"), from = "2026-10-14", to = "2026-10-14", includeTransfers = true, includeDuplicates = true),
            ReviewLogic.blotterFilter("amex", "2026-10-14T00:00:00"),
        )
        assertEquals(LedgerFilter(includeTransfers = true, includeDuplicates = true), ReviewLogic.blotterFilter("", ""))
    }
}
