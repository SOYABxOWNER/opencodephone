package io.github.oustery.opencodephone.core.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MessageTimelineTest {
    private fun message(id: String, created: Long?) = MessageWithParts(MessageInfo(id = id,
        time = created?.let { buildJsonObject { put("created", it) } }))

    @Test fun `two turns normalize newest first regardless of response ordering`() {
        val first = message("user1", 10)
        val second = message("assistant1", 20)
        val third = message("user2", 30)
        val fourth = message("assistant2", 40)
        val expected = listOf("assistant2", "user2", "assistant1", "user1")
        assertEquals(expected, MessageTimeline.newestFirst(listOf(third, first, fourth, second)).map { it.info.id })
        assertEquals(expected, MessageTimeline.newestFirst(listOf(fourth, third, second, first)).map { it.info.id })
    }
    @Test fun `missing and equal timestamps retain reverse chronological API order`() {
        assertEquals(listOf("b", "a"), MessageTimeline.newestFirst(listOf(message("a", null), message("b", null))).map { it.info.id })
        assertEquals(listOf("b", "a"), MessageTimeline.newestFirst(listOf(message("a", 10), message("b", 10))).map { it.info.id })
        assertTrue(MessageTimeline.newestFirst(emptyList()).isEmpty())
    }
}
