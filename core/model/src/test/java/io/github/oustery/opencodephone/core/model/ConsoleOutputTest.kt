package io.github.oustery.opencodephone.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleOutputTest {
    @Test fun `partial lines are visible before newline and joined across reads`() {
        val output = ConsoleOutput()
        assertEquals(listOf("hel"), output.append("hel"))
        assertEquals(listOf("hello", "next"), output.append("lo\r\nnext"))
        assertEquals(listOf("hello", "next"), output.append("\n"))
    }

    @Test fun `line count and single line size are bounded and clear resets partial state`() {
        val output = ConsoleOutput(maxLines = 2, maxLineLength = 4)
        assertEquals(listOf("two", "tri"), output.append("one\ntwo\ntri"))
        output.clear()
        assertEquals(listOf("abcd", "e"), output.append("abcde"))
    }
}
