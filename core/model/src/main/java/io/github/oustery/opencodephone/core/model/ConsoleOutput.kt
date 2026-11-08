package io.github.oustery.opencodephone.core.model

/** Bounded pipe output, including a last line that has no trailing newline. */
class ConsoleOutput(private val maxLines: Int = 400, private val maxLineLength: Int = 8192) {
    private val lines = ArrayDeque<String>()
    private val partial = StringBuilder()

    init { require(maxLines > 0 && maxLineLength > 0) }

    @Synchronized
    fun append(text: String): List<String> {
        for (char in text) {
            when (char) {
                '\r' -> Unit
                '\n' -> finishLine()
                else -> {
                    partial.append(char)
                    if (partial.length >= maxLineLength) finishLine()
                }
            }
        }
        return (lines.toList() + if (partial.isNotEmpty()) listOf(partial.toString()) else emptyList()).takeLast(maxLines)
    }

    private fun finishLine() {
        lines.addLast(partial.toString())
        partial.setLength(0)
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        partial.setLength(0)
    }
}
