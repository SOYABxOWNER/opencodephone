package io.github.oustery.opencodephone.core.designsystem

/**
 * A deliberately small Markdown model + parser for agent chat (no Compose and
 * no Android imports, so the whole grammar is unit-testable on the JVM).
 *
 * Supported blocks: ATX headings, fenced code (backtick and tilde, optional
 * language), blockquotes (recursive), bullet/ordered lists with one visual
 * nesting level per 2 spaces of indentation, GFM pipe tables, horizontal
 * rules and paragraphs. An unterminated fence — the normal state while the
 * agent is still streaming — degrades to a code block to the end of input
 * instead of flickering between paragraph and code.
 *
 * Supported inline spans: bold (`**`/`__`), italic (`*`/`_` with CommonMark
 * word-boundary rules for `_` so `snake_case` stays literal), strikethrough,
 * inline code, `[label](url)` links, `![alt](url)` images rendered as links,
 * `<https://…>` autolinks and bare `http(s)://…` URLs. Link URLs are limited
 * to http/https/mailto; anything else — including raw HTML — renders as
 * literal text, which is the safe default for agent output.
 *
 * Intentionally not supported (kept literal): setext headings, indented code
 * blocks, reference links/footnotes, raw HTML, entity references.
 */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Code(val language: String?, val code: String, val terminated: Boolean) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val items: List<MdListItem>) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** One list entry; [indent] is the visual nesting level (0..3). */
data class MdListItem(val indent: Int, val number: Int?, val spans: List<MdSpan>)

sealed interface MdSpan {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
    ) : MdSpan

    data class CodeSpan(val code: String) : MdSpan

    /** `![alt](url)` images render as links labelled with the alt text. */
    data class Link(val label: String, val url: String) : MdSpan
}

object MarkdownParser {

    private val fence = Regex("""^ {0,3}(`{3,}|~{3,})\s*([A-Za-z0-9_+\-.#]*)\s*$""")
    private val heading = Regex("""^ {0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val horizontalRule = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
    private val listItem = Regex("""^(\s*)([-*+]|\d{1,9}[.)])([ \t]+)(.*)$""")
    private val quoteLine = Regex("""^ {0,3}>""")
    private val autolink = Regex("""^<(https?://[^ <>]+|mailto:[^ <>]+)>""")
    private val bareUrl = Regex("""https?://\S""")
    private val delimiterCell = Regex("""^:?-+:?$""")

    // ---------------------------------------------------------------- blocks

    fun parseDocument(source: String): List<MdBlock> = parseBlocks(source.lines())

    private fun parseBlocks(lines: List<String>): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> i++

                fence.containsMatchIn(line) -> {
                    val match = fence.matchEntire(line)!!
                    val marker = match.groupValues[1]
                    val language = match.groupValues[2].trim().ifBlank { null }
                    val code = StringBuilder()
                    var terminated = false
                    i++
                    while (i < lines.size) {
                        val inner = lines[i]
                        if (inner.trim().startsWith(marker) &&
                            inner.trim().all { it == marker[0] } &&
                            inner.trim().length >= marker.length
                        ) {
                            terminated = true
                            i++
                            break
                        }
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(inner)
                        i++
                    }
                    blocks += MdBlock.Code(language, code.toString(), terminated)
                }

                heading.containsMatchIn(line) -> {
                    val match = heading.matchEntire(line)!!
                    blocks += MdBlock.Heading(
                        level = match.groupValues[1].length,
                        spans = parseInline(match.groupValues[2]),
                    )
                    i++
                }

                horizontalRule.containsMatchIn(line) -> {
                    blocks += MdBlock.Rule
                    i++
                }

                quoteLine.containsMatchIn(line) -> {
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && quoteLine.containsMatchIn(lines[i])) {
                        quoted += lines[i].replaceFirst(quoteLine, "").removePrefix(" ")
                        i++
                    }
                    blocks += MdBlock.Quote(parseBlocks(quoted))
                }

                listItem.containsMatchIn(line) -> i = parseList(lines, i, blocks)

                startsTable(lines, i) -> {
                    val header = splitTableRow(lines[i])
                    var j = i + 2
                    val rows = mutableListOf<List<String>>()
                    while (j < lines.size && lines[j].isNotBlank() && lines[j].contains('|')) {
                        rows += splitTableRow(lines[j]).take(header.size)
                            .let { cells -> cells + List(header.size - cells.size) { "" } }
                        j++
                    }
                    blocks += MdBlock.Table(header, rows)
                    i = j
                }

                else -> {
                    val paragraph = mutableListOf(line)
                    i++
                    // A paragraph ends at a blank line or at the start of the
                    // next block; blank lines are never paragraph content.
                    while (i < lines.size && lines[i].isNotBlank() && !startsWithBlock(lines, i)) {
                        paragraph += lines[i]
                        i++
                    }
                    blocks += MdBlock.Paragraph(parseInline(paragraph.joinToString("\n")))
                }
            }
        }
        return blocks
    }

    private fun parseList(lines: List<String>, start: Int, blocks: MutableList<MdBlock>): Int {
        val firstMatch = listItem.matchEntire(lines[start])!!
        val items = mutableListOf<MdListItem>()
        // A marker-type change (bullet -> number) starts a new list, matching
        // the CommonMark behaviour users expect from mixed fragments.
        var ordered = firstMatch.groupValues[2].first().isDigit()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                // A blank line keeps the list open only when another item follows.
                val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: break
                if (listItem.containsMatchIn(lines[next])) {
                    i = next
                    continue
                }
                break
            }
            val match = listItem.matchEntire(line)
            if (match != null) {
                val itemOrdered = match.groupValues[2].first().isDigit()
                if (itemOrdered != ordered) {
                    blocks += MdBlock.ListBlock(ordered, items)
                    ordered = itemOrdered
                    items = mutableListOf()
                }
                val indent = minOf(match.groupValues[1].length / 2, 3)
                val number = match.groupValues[2].trimEnd('.', ')').toIntOrNull()
                val content = StringBuilder(match.groupValues[4])
                i++
                // Lazy continuation: an indented non-item line extends the item.
                while (i < lines.size && lines[i].isNotBlank() && !listItem.containsMatchIn(lines[i]) &&
                    (lines[i].startsWith("  ") || !startsWithBlock(lines, i))
                ) {
                    content.append('\n').append(lines[i].trim())
                    i++
                }
                items += MdListItem(
                    indent = indent,
                    number = if (ordered) number else null,
                    spans = parseInline(content.toString()),
                )
            } else {
                break
            }
        }
        blocks += MdBlock.ListBlock(ordered, items)
        return i
    }

    private fun startsBlock(lines: List<String>, index: Int): Boolean =
        startsTable(lines, index) ||
            fence.containsMatchIn(lines[index]) ||
            heading.containsMatchIn(lines[index]) ||
            horizontalRule.containsMatchIn(lines[index]) ||
            quoteLine.containsMatchIn(lines[index]) ||
            listItem.containsMatchIn(lines[index])

    private fun startsTable(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size || !lines[index].contains('|')) return false
        val header = splitTableRow(lines[index])
        if (header.size < 2) return false
        val delimiter = splitTableRow(lines[index + 1])
        return delimiter.size >= 2 && delimiter.all { delimiterCell.matches(it) }
    }

    /** Splits a GFM table row on unescaped pipes, trimming boundary cells. */
    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim()
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < trimmed.length) {
            val c = trimmed[i]
            when {
                c == '\\' && i + 1 < trimmed.length && trimmed[i + 1] == '|' -> {
                    current.append('|')
                    i += 2
                }

                c == '|' -> {
                    cells += current.toString().trim()
                    current.clear()
                    i++
                }

                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        cells += current.toString().trim()
        if (trimmed.startsWith("|") && cells.isNotEmpty() && cells.first().isEmpty()) cells.removeAt(0)
        if (trimmed.endsWith("|") && cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.size - 1)
        return cells
    }

    // ---------------------------------------------------------------- inline

    private data class Flags(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
    )

    fun parseInline(source: String): List<MdSpan> = spans(source, Flags())

    private fun spans(src: String, flags: Flags): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                out += MdSpan.Text(buffer.toString(), flags.bold, flags.italic, flags.strike)
                buffer.clear()
            }
        }

        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\\' && i + 1 < src.length && src[i + 1].isAsciiPunctuation() -> {
                    buffer.append(src[i + 1])
                    i += 2
                }

                c == '`' -> {
                    val run = runLength(src, i, '`')
                    val close = findBacktickRun(src, i + run, run)
                    if (close == -1) {
                        buffer.append(src, i, i + run)
                        i += run
                    } else {
                        var code = src.substring(i + run, close)
                        if (code.length >= 2 && code.first() == ' ' && code.last() == ' ') {
                            code = code.substring(1, code.length - 1)
                        }
                        flush()
                        out += MdSpan.CodeSpan(code)
                        i = close + run
                    }
                }

                c == '*' || c == '_' -> {
                    val double = src.startsWith("$c$c", i)
                    val marker = if (double) "$c$c" else "$c"
                    val openerOk = if (c == '_') {
                        (i == 0 || src[i - 1].isWhitespace() || src[i - 1].isAsciiPunctuation())
                    } else true
                    val next = i + marker.length
                    if (!openerOk || next >= src.length || src[next].isWhitespace()) {
                        buffer.append(c)
                        i++
                    } else {
                        val boundaryAfter = c == '_'
                        val close = findClose(src, next, marker, boundaryAfter)
                        if (close == -1) {
                            buffer.append(c)
                            i++
                        } else {
                            val inner = src.substring(next, close)
                            if (inner.first().isWhitespace() || inner.last().isWhitespace()) {
                                buffer.append(c)
                                i++
                            } else {
                                flush()
                                out += spans(
                                    inner,
                                    flags.copy(
                                        bold = flags.bold || double,
                                        italic = flags.italic || !double,
                                    ),
                                )
                                i = close + marker.length
                            }
                        }
                    }
                }

                c == '~' && src.startsWith("~~", i) && i + 2 < src.length && !src[i + 2].isWhitespace() -> {
                    val close = findClose(src, i + 2, "~~")
                    if (close == -1) {
                        buffer.append(c)
                        i++
                    } else {
                        val inner = src.substring(i + 2, close)
                        if (inner.first().isWhitespace() || inner.last().isWhitespace()) {
                            buffer.append(c)
                            i++
                        } else {
                            flush()
                            out += spans(inner, flags.copy(strike = true))
                            i = close + 2
                        }
                    }
                }

                c == '!' && i + 1 < src.length && src[i + 1] == '[' -> {
                    val link = tryLink(src, i + 1)
                    if (link != null) {
                        flush()
                        out += MdSpan.Link(link.label.ifBlank { link.url }, link.url)
                        i = link.end
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                c == '[' -> {
                    val link = tryLink(src, i)
                    if (link != null) {
                        flush()
                        out += MdSpan.Link(link.label, link.url)
                        i = link.end
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                c == '<' -> {
                    val match = autolink.matchAt(src, i)
                    if (match != null) {
                        val url = match.value.substring(1, match.value.length - 1)
                        flush()
                        out += MdSpan.Link(url, url)
                        i += match.value.length
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                c == 'h' && bareUrl.matchAt(src, i) != null && linkBoundaryBefore(src, i) -> {
                    var end = i
                    while (end < src.length && !src[end].isWhitespace() && src[end] != '<') end++
                    var url = src.substring(i, end)
                    // Keep sentence punctuation out of the target.
                    url = url.trimEnd('.', ',', ';', ':', '!', '?')
                    if (url.endsWith(")") && !url.contains('(')) url = url.removeSuffix(")")
                    flush()
                    out += MdSpan.Link(url, url)
                    i = i + url.length
                }

                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    private data class LinkMatch(val label: String, val url: String, val end: Int)

    private fun tryLink(src: String, at: Int): LinkMatch? {
        val labelClose = src.indexOf(']', at + 1)
        if (labelClose == -1 || labelClose + 1 >= src.length || src[labelClose + 1] != '(') return null
        val parenClose = src.indexOf(')', labelClose + 2)
        if (parenClose == -1) return null
        val label = src.substring(at + 1, labelClose)
        val url = src.substring(labelClose + 2, parenClose).trim()
            .removeSurrounding("<", ">")
        if (label.isEmpty() || !isSafeUrl(url)) return null
        return LinkMatch(label, url, parenClose + 1)
    }

    /** Only schemes that are safe to hand to the platform URI handler. */
    private fun isSafeUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")

    private fun linkBoundaryBefore(src: String, i: Int): Boolean =
        i == 0 || src[i - 1].isWhitespace() || src[i - 1] in "(<[{\"'"

    private fun findClose(src: String, from: Int, marker: String, boundaryAfter: Boolean = false): Int {
        var j = from
        while (j < src.length) {
            if (src.startsWith(marker, j)) {
                val run = runLength(src, j, marker[0])
                // A longer run of the same delimiter surrounds a nested span
                // ("***bi***" closes around "*bi*"), so position the closing
                // marker at the END of the run when it is longer than ours.
                val close = if (run > marker.length) j + run - marker.length else j
                val innerNonEmpty = close > from && !src[close - 1].isWhitespace()
                val after = close + marker.length
                val afterOk = !boundaryAfter ||
                    after >= src.length || src[after].isWhitespace() || src[after].isAsciiPunctuation()
                if (innerNonEmpty && afterOk) return close
                j += run
            } else {
                j++
            }
        }
        return -1
    }

    private fun runLength(src: String, from: Int, c: Char): Int {
        var n = 0
        while (from + n < src.length && src[from + n] == c) n++
        return n
    }

    /** Closing run of exactly [n] backticks (a longer run cannot close a shorter one). */
    private fun findBacktickRun(src: String, from: Int, n: Int): Int {
        var j = from
        while (j < src.length) {
            if (src[j] == '`') {
                val run = runLength(src, j, '`')
                if (run == n) return j
                j += run
            } else {
                j++
            }
        }
        return -1
    }

    private fun Char.isAsciiPunctuation(): Boolean = this in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
}
