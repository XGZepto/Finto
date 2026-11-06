package page.zepto.finto.feature.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web app's `answer-format.spec.ts`, case for case, plus the edges streaming produces. */
class AnswerMarkdownTest {

    private fun plain(text: String) = AnswerPart(text)
    private fun bold(text: String) = AnswerPart(text, strong = true)

    @Test
    fun emphasisAndBulletListsBecomeStructureNotRawMarkers() {
        assertEquals(
            listOf(
                AnswerBlock.Paragraph(listOf(plain("Dining totalled "), bold("HKD 119,065"), plain("."))),
                AnswerBlock.Bullets(
                    ordered = false,
                    items = listOf(listOf(plain("HKD cards: 69,915")), listOf(plain("USD converted: 27,204"))),
                ),
            ),
            AnswerMarkdown.parse("Dining totalled **HKD 119,065**.\n\n- HKD cards: 69,915\n- USD converted: 27,204"),
        )
    }

    @Test
    fun headingsAndOrderedLists() {
        assertEquals(
            listOf(
                AnswerBlock.Heading(listOf(plain("Detail"))),
                AnswerBlock.Bullets(ordered = true, items = listOf(listOf(plain("First")), listOf(bold("Second")))),
            ),
            AnswerMarkdown.parse("### Detail\n1. First\n2. **Second**"),
        )
    }

    @Test
    fun tablesAlignTheirFigureColumns() {
        val blocks = AnswerMarkdown.parse("By month:\n\n| Month | HKD | USD |\n|---|---|---|\n| Jan | — | 47.00 |\n| Feb | 195.74 | 14.32 |")
        assertEquals(2, blocks.size)
        val table = blocks[1] as AnswerBlock.Table
        assertEquals(listOf("Month", "HKD", "USD"), table.header.map { it.first().text })
        assertEquals(2, table.rows.size)
        assertEquals(listOf(false, true, true), table.numeric)
    }

    @Test
    fun aTableStillStreamingInDoesNotSwallowTheTextAfterIt() {
        val blocks = AnswerMarkdown.parse("| Month | HKD |\n|---|---|\n| Jan | 1.00 |\nThat was all.")
        assertEquals(listOf(AnswerBlock.Table::class, AnswerBlock.Paragraph::class), blocks.map { it::class })
    }

    @Test
    fun aTableWithOnlyItsHeaderSoFarReadsAsALine() {
        val blocks = AnswerMarkdown.parse("| Month | HKD |\n|---|---|")
        assertEquals(listOf(AnswerBlock.Paragraph(listOf(plain("Month · HKD")))), blocks)
    }

    @Test
    fun shortRowsArePaddedToTheHeader() {
        val table = AnswerMarkdown.parse("| A | B | C |\n|---|---|---|\n| x | 1 |").single() as AnswerBlock.Table
        assertEquals(3, table.rows.single().size)
        assertEquals("", table.rows.single()[2].single().text)
    }

    @Test
    fun linesOfAParagraphJoinAndListKindsDoNotMix() {
        val blocks = AnswerMarkdown.parse("One\ntwo\n- a\n1. b\nthree")
        assertEquals(
            listOf(
                AnswerBlock.Paragraph(listOf(plain("One two"))),
                AnswerBlock.Bullets(false, listOf(listOf(plain("a")))),
                AnswerBlock.Bullets(true, listOf(listOf(plain("b")))),
                AnswerBlock.Paragraph(listOf(plain("three"))),
            ),
            blocks,
        )
    }

    @Test
    fun anUnclosedBoldStaysLiteralUntilItCloses() {
        assertEquals(listOf(plain("Spent **HKD 1")), AnswerMarkdown.inline("Spent **HKD 1"))
        assertEquals(listOf(plain("Spent "), bold("HKD 1")), AnswerMarkdown.inline("Spent **HKD 1**"))
    }

    @Test
    fun boldAtTheStartOfALineIsNotABullet() {
        val blocks = AnswerMarkdown.parse("**Total** was 12.")
        assertEquals(listOf(AnswerBlock.Paragraph(listOf(bold("Total"), plain(" was 12.")))), blocks)
    }

    @Test
    fun emptyAndWindowsLineEndings() {
        assertTrue(AnswerMarkdown.parse("").isEmpty())
        assertEquals(2, AnswerMarkdown.parse("a\r\n\r\nb").size)
    }
}
