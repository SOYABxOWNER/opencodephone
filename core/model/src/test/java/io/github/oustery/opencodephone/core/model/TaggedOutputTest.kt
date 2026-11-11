package io.github.oustery.opencodephone.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaggedOutputTest {

    @Test
    fun `lines get stable ids across appends`() {
        val out = TaggedOutput()
        val first = out.append("hello\n")
        val second = out.append("world\n")
        // append returns the full capped snapshot; the terminal state holds it
        // verbatim (TerminalViewModel.appendOutput), so ids stay stable while
        // the buffer grows.
        assertEquals(listOf("hello"), first.map { it.text })
        assertEquals(listOf("hello", "world"), second.map { it.text })
        assertTrue(second.last().id > first.single().id)
    }

    @Test
    fun `partial line keeps its id while growing`() {
        val out = TaggedOutput()
        val a = out.append("par")
        val b = out.append("tial\n")
        assertEquals(a.single().id, b.first().id)
        assertEquals("partial", b.first().text)
        assertEquals(1, b.size)
    }

    @Test
    fun `trims from the front beyond the cap`() {
        val out = TaggedOutput(maxLines = 3)
        val snapshot = out.append("a\nb\nc\nd\n")
        assertEquals(listOf("b", "c", "d"), snapshot.map { it.text })
    }

    @Test
    fun `carriage returns are dropped and last line without newline is included`() {
        val out = TaggedOutput()
        val snapshot = out.append("pro\rgress\r")
        assertEquals(listOf("progress"), snapshot.map { it.text })
        out.clear()
        assertEquals(0, out.append("").size)
    }
}
