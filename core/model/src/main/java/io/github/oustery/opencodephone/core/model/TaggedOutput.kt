package io.github.oustery.opencodephone.core.model

/**
 * [ConsoleOutput] twin that assigns a stable id to every line. The terminal
 * LazyColumn keys rows by [Line.id], so a ring-buffer trim (which drops the
 * oldest line and shifts every positional index) no longer re-composes the
 * whole visible console while a command streams.
 */
class TaggedOutput(private val maxLines: Int = 400, private val maxLineLength: Int = 8192) {

    data class Line(val id: Long, val text: String)

    private val lines = ArrayDeque<Line>()
    private var partial: Line? = null
    private var nextId = 0L

    init { require(maxLines > 0 && maxLineLength > 0) }

    @Synchronized
    fun append(text: String): List<Line> {
        for (char in text) {
            when (char) {
                '\r' -> Unit
                '\n' -> finishLine()
                else -> {
                    val current = partial ?: Line(nextId++, "").also { partial = it }
                    val grown = current.copy(text = current.text + char)
                    partial = grown
                    if (grown.text.length >= maxLineLength) finishLine()
                }
            }
        }
        return (lines.toList() + listOfNotNull(partial)).takeLast(maxLines)
    }

    private fun finishLine() {
        val line = partial ?: Line(nextId++, "")
        lines.addLast(line)
        partial = null
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        partial = null
    }
}
