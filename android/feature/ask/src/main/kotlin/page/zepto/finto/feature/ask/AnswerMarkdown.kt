package page.zepto.finto.feature.ask

/** A run of answer text, bold or not. */
data class AnswerPart(val text: String, val strong: Boolean = false)

/** The Markdown subset answers are written in: headings, paragraphs, lists and pipe tables. */
sealed interface AnswerBlock {
    data class Heading(val parts: List<AnswerPart>) : AnswerBlock
    data class Paragraph(val parts: List<AnswerPart>) : AnswerBlock
    data class Bullets(val ordered: Boolean, val items: List<List<AnswerPart>>) : AnswerBlock
    data class Table(
        val header: List<List<AnswerPart>>,
        val rows: List<List<List<AnswerPart>>>,
        /** Columns whose every cell reads as a figure, aligned to the end. */
        val numeric: List<Boolean>,
    ) : AnswerBlock
}

/**
 * Parses an answer the way the web app's `parseAnswerMarkdown` does, so a
 * half-streamed answer is always renderable: a table still arriving never
 * swallows the line after it, and nothing becomes markup.
 */
object AnswerMarkdown {

    /** A cell that reads as a figure aligns right, like every other ledger column. */
    private val figure = Regex("""^[-+(]?\s*(?:[A-Z]{3}\s*)?[-+]?[\d,]+(?:\.\d+)?%?\)?(?:\s*[A-Z]{3})?$|^[—–-]$""")
    private val divider = Regex("""^:?-{2,}:?$""")
    private val heading = Regex("""^#{1,6}\s+(.+)$""")
    private val bullet = Regex("""^[-*]\s+(.+)$""")
    private val numbered = Regex("""^\d+[.)]\s+(.+)$""")
    private val strong = Regex("""\*\*(.+?)\*\*""")

    fun parse(source: String): List<AnswerBlock> {
        val blocks = mutableListOf<AnswerBlock>()
        val lines = source.replace(Regex("\r\n?"), "\n").trim().split('\n')
        val paragraph = mutableListOf<String>()
        var list: Pair<Boolean, MutableList<List<AnswerPart>>>? = null
        var table: MutableList<List<String>>? = null

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            blocks += AnswerBlock.Paragraph(inline(paragraph.joinToString(" ")))
            paragraph.clear()
        }

        fun flushList() {
            val current = list ?: return
            blocks += AnswerBlock.Bullets(current.first, current.second.toList())
            list = null
        }

        fun flushTable() {
            val current = table ?: return
            val rows = current.filterNot { cells -> cells.all { divider.matches(it) } }
            val header = rows.firstOrNull()
            val body = rows.drop(1)
            if (header != null && body.isNotEmpty()) {
                val width = header.size
                val numeric = header.indices.map { column ->
                    body.all { row -> figure.matches(row.getOrElse(column) { "" }.replace("**", "").trim()) }
                }
                blocks += AnswerBlock.Table(
                    header = header.map(::inline),
                    rows = body.map { row -> List(width) { column -> inline(row.getOrElse(column) { "" }) } },
                    numeric = numeric,
                )
            } else if (header != null) {
                blocks += AnswerBlock.Paragraph(inline(header.joinToString(" · ")))
            }
            table = null
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) {
                flushParagraph()
                flushList()
                flushTable()
                continue
            }

            if (line.startsWith("|")) {
                flushParagraph()
                flushList()
                table = (table ?: mutableListOf()).apply { add(splitRow(line)) }
                continue
            }
            flushTable()

            val title = heading.matchEntire(line)?.groupValues?.get(1)
            if (title != null) {
                flushParagraph()
                flushList()
                blocks += AnswerBlock.Heading(inline(title))
                continue
            }

            val bulletItem = bullet.matchEntire(line)?.groupValues?.get(1)
            val numberedItem = numbered.matchEntire(line)?.groupValues?.get(1)
            val item = bulletItem ?: numberedItem
            if (item != null) {
                flushParagraph()
                val ordered = bulletItem == null
                val open = list?.takeIf { it.first == ordered } ?: run {
                    flushList()
                    (ordered to mutableListOf<List<AnswerPart>>()).also { list = it }
                }
                open.second += inline(item)
                continue
            }

            flushList()
            paragraph += line
        }

        flushParagraph()
        flushList()
        flushTable()
        return blocks
    }

    /** `Dining **HKD 119**.` → plain, bold, plain. */
    fun inline(value: String): List<AnswerPart> {
        val parts = mutableListOf<AnswerPart>()
        var cursor = 0
        for (match in strong.findAll(value)) {
            if (match.range.first > cursor) parts += AnswerPart(value.substring(cursor, match.range.first))
            parts += AnswerPart(match.groupValues[1], strong = true)
            cursor = match.range.last + 1
        }
        if (cursor < value.length) parts += AnswerPart(value.substring(cursor))
        return parts.ifEmpty { listOf(AnswerPart(value)) }
    }

    private fun splitRow(line: String): List<String> =
        line.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}
