package io.github.oustery.opencodephone.core.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartsTest {

    @Test
    fun `parses text part`() {
        val el = OcJson.lenient.parseToJsonElement("""{"type":"text","text":"hello world"}""")
        val part = Parts.parse(el)
        assertTrue(part is UiPart.Text)
        assertEquals("hello world", (part as UiPart.Text).text)
    }

    @Test
    fun `parses tool part with state`() {
        val el = OcJson.lenient.parseToJsonElement(
            """{"type":"tool","tool":"bash","state":{"status":"completed","title":"Run tests","output":"ok"}}""",
        )
        val part = Parts.parse(el)
        assertTrue(part is UiPart.Tool)
        val tool = part as UiPart.Tool
        assertEquals("bash", tool.tool)
        assertEquals("completed", tool.status)
        assertEquals("Run tests", tool.title)
        assertEquals("ok", tool.output)
    }

    @Test
    fun `parses file part with filename`() {
        val el = OcJson.lenient.parseToJsonElement(
            """{"type":"file","mime":"image/png","filename":"photo.png","url":"file:///root/projects/uploads/photo.png"}""",
        )
        val part = Parts.parse(el)
        assertTrue(part is UiPart.FileAttachment)
        val file = part as UiPart.FileAttachment
        assertEquals("photo.png", file.name)
        assertEquals("image/png", file.mime)
    }

    @Test
    fun `file part without filename falls back to url basename`() {
        val el = OcJson.lenient.parseToJsonElement(
            """{"type":"file","mime":"text/plain","url":"file:///root/projects/uploads/report.txt"}""",
        )
        val part = Parts.parse(el)
        assertEquals("report.txt", (part as UiPart.FileAttachment).name)
    }

    @Test
    fun `unknown part type degrades safely`() {
        val el = OcJson.lenient.parseToJsonElement("""{"type":"voice-note","mime":"audio/ogg"}""")
        assertEquals(UiPart.Unknown, Parts.parse(el))
    }

    @Test
    fun `malformed element never throws`() {
        val el = JsonPrimitive("just a string")
        assertEquals(UiPart.Unknown, Parts.parse(el))
    }

    @Test
    fun `step markers are filtered from ui parts`() {
        val el = OcJson.lenient.parseToJsonElement(
            """[{"type":"step-start"},{"type":"text","text":"a"},{"type":"step-finish"}]""",
        )
        val ui = Parts.uiParts(el.jsonArray)
        assertEquals(1, ui.size)
        assertEquals("a", (ui[0] as UiPart.Text).text)
    }
}
