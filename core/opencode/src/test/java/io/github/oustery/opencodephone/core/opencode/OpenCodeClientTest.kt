package io.github.oustery.opencodephone.core.opencode

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenCodeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenCodeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenCodeClient(
            config = ConnectionConfig(host = server.hostName, port = server.port),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses health`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"healthy":true,"version":"1.19.5"}"""),
        )
        val health = client.health()
        assertEquals(true, health.healthy)
        assertEquals("1.19.5", health.version)
        val recorded = server.takeRequest()
        assertEquals("/global/health", recorded.path)
    }

    @Test
    fun `parses sessions with unknown fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"ses_1","title":"Fix bug","directory":"/root/projects/app",
                    "unknownField":42,"time":{"created":1,"updated":2}},
                   {"id":"ses_2","title":""}]""",
            ),
        )
        val sessions = client.listSessions()
        assertEquals(2, sessions.size)
        assertEquals("ses_1", sessions[0].id)
        assertEquals("Fix bug", sessions[0].title)
    }

    @Test
    fun `create session sends title and parses response`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"ses_new","title":"t"}"""))
        val session = client.createSession("t")
        assertEquals("ses_new", session.id)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/session", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"title\":\"t\""))
    }

    @Test
    fun `prompt async builds correct body and tolerates 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        client.promptAsync(
            sessionId = "ses_1",
            text = "hello",
            providerId = "anthropic",
            modelId = "claude-sonnet-4-5",
            agent = "build",
        )
        val recorded = server.takeRequest()
        assertEquals("/session/ses_1/prompt_async", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"type\":\"text\""))
        assertTrue(body.contains("claude-sonnet-4-5"))
        assertTrue(body.contains("\"agent\":\"build\""))
    }

    @Test
    fun `prompt async with attachments sends V1 file parts`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        client.promptAsync(
            sessionId = "ses_1",
            text = "check this",
            providerId = null,
            modelId = null,
            agent = null,
            attachments = listOf(
                PromptAttachment(
                    path = "/root/projects/uploads/report.txt",
                    mime = "text/plain",
                    filename = "report.txt",
                ),
                PromptAttachment(path = "/root/projects/uploads/img.png", mime = "", filename = "img.png"),
            ),
        )
        val recorded = server.takeRequest()
        assertEquals("/session/ses_1/prompt_async", recorded.path)
        val body = recorded.body.readUtf8()
        // Text part first, then one file part per attachment in order.
        assertTrue(body.contains("\"type\":\"text\""))
        assertTrue(body.contains("\"text\":\"check this\""))
        assertTrue(body.contains("\"type\":\"file\""))
        assertTrue(body.contains("\"mime\":\"text/plain\""))
        assertTrue(body.contains("\"filename\":\"report.txt\""))
        assertTrue(body.contains("\"url\":\"file:///root/projects/uploads/report.txt\""))
        assertTrue(body.contains("\"mime\":\"application/octet-stream\""))
        assertTrue(body.contains("\"url\":\"file:///root/projects/uploads/img.png\""))
    }

    @Test
    fun `prompt body skips empty text part when only files are sent`() {
        val body = promptBody("   ", providerId = null, modelId = null, agent = null, attachments = listOf(
            PromptAttachment(path = "/root/projects/uploads/a.png", mime = "image/png", filename = "a.png"),
        ))
        val text = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), body,
        )
        assertTrue(text.contains("\"type\":\"file\""))
        assertTrue(!text.contains("\"type\":\"text\""))
    }

    @Test
    fun `inline text rides along as a second text part for remote delivery`() {
        val body = promptBody(
            "check this",
            providerId = null, modelId = null, agent = null,
            attachments = emptyList(),
            inlineText = "Attached file \"notes.md\":\n```markdown\nhello\n```",
        )
        val text = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), body,
        )
        assertTrue(text.contains("\"text\":\"check this\""))
        assertTrue(text.contains("Attached file \\\"notes.md\\\""))
    }

    @Test
    fun `attachment inlining classifier covers text and code files`() {
        assertTrue(Attachments.isInlineable("text/plain", "notes.txt"))
        assertTrue(Attachments.isInlineable("application/json", "package.json"))
        assertTrue(Attachments.isInlineable("", "MainActivity.kt"))
        assertTrue(Attachments.isInlineable("application/octet-stream", ".gitignore"))
        assertFalse(Attachments.isInlineable("image/png", "photo.png"))
        assertFalse(Attachments.isInlineable("application/pdf", "spec.pdf"))
        assertFalse(Attachments.isInlineable("", "archive.zip"))
        val block = Attachments.inlineBlock("notes.md", "hello")
        assertTrue(block.startsWith("Attached file \"notes.md\":\n```markdown\n"))
        assertTrue(block.endsWith("```"))
    }

    @Test
    fun `messages pagination sends limit and before and reads next cursor`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("X-Next-Cursor", "msg_3")
                .setBody("""[{"info":{"id":"msg_1","role":"user"},"parts":[]},
                    {"info":{"id":"msg_2","role":"assistant"},"parts":[]}]"""),
        )
        val page = client.listMessages("ses_1", limit = 2)
        assertEquals(2, page.items.size)
        assertEquals("msg_3", page.nextCursor)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/session/ses_1/message?"))
        assertTrue(recorded.path!!.contains("limit=2"))
    }

    @Test
    fun `messages pagination passes encoded before cursor`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        client.listMessages("ses_1", limit = 5, before = "msg 3/id")
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("limit=5"))
        assertTrue(recorded.path!!.contains("before=msg+3%2Fid"))
    }

    @Test
    fun `messages without limit fetches everything and has no cursor`() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"info":{"id":"msg_1","role":"user"},"parts":[]}]"""))
        val page = client.listMessages("ses_1")
        assertEquals(1, page.items.size)
        assertEquals(null, page.nextCursor)
        assertEquals("/session/ses_1/message", server.takeRequest().path)
    }

    @Test
    fun `rename session patches title`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"ses_1","title":"Renamed"}"""))
        val session = client.renameSession("ses_1", "Renamed")
        assertEquals("Renamed", session.title)
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/session/ses_1", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"title\":\"Renamed\""))
    }

    @Test
    fun `session diff parses FileDiff shape`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"file":"src/App.kt","patch":"@@ -1 +1 @@","additions":3,"deletions":1,"status":"modified"},
                   {"file":"new.txt","additions":5,"deletions":0,"status":"added"}]""",
            ),
        )
        val diff = client.sessionDiff("ses_1")
        assertEquals(2, diff.size)
        assertEquals("src/App.kt", diff[0].file)
        assertEquals("@@ -1 +1 @@", diff[0].patch)
        assertEquals(3L, diff[0].additions)
        assertEquals(1L, diff[0].deletions)
        assertEquals("modified", diff[0].status)
        assertEquals("added", diff[1].status)
        assertEquals(null, diff[1].patch)
    }

    @Test
    fun `sessions limit is passed as a query parameter`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        client.listSessions(limit = 100)
        assertEquals("/session?limit=100", server.takeRequest().path)
    }

    @Test
    fun `basic auth header is attached when password configured`() = runBlocking {
        client.config = client.config.copy(username = "opencode", password = "secret")
        server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.19.5"}"""))
        client.health()
        val recorded = server.takeRequest()
        assertNotNull(recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun `empty username uses the same default as the local server`() = runBlocking {
        client.config = client.config.copy(username = "", password = "secret")
        server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.19.5"}"""))
        client.health()
        assertEquals(okhttp3.Credentials.basic("opencode", "secret"), server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `api error raises OpenCodeApiException`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        var thrown: OpenCodeApiException? = null
        runBlocking {
            try {
                client.health()
            } catch (e: OpenCodeApiException) {
                thrown = e
            }
        }
        assertEquals(500, thrown?.code)
    }

    @Test
    fun `HTML web fallback reports protocol mismatch not JSON parser internals`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<!doctype html><title>OpenCode</title>"))
        try {
            client.health()
            org.junit.Assert.fail("HTML cannot be health")
        } catch (e: OpenCodeApiException) {
            assertTrue(e.message!!.contains("received HTML"))
            assertTrue(e.message!!.contains("V2"))
        }
        assertEquals("application/json", server.takeRequest().getHeader("Accept"))
    }

    @Test
    fun `200 arbitrary JSON is not a healthy server`() = runBlocking {
        for (body in listOf("{}", "{\"healthy\":false,\"version\":\"1.19.5\"}",
            "{\"healthy\":true,\"version\":\"2.2.6\"}")) {
            server.enqueue(MockResponse().setBody(body))
            try {
                client.health()
                org.junit.Assert.fail("Invalid health accepted: $body")
            } catch (_: OpenCodeApiException) { }
        }
    }

    @Test
    fun `invalid JSON has endpoint context without exposing response body`() = runBlocking {
        server.enqueue(MockResponse().setBody("not-json-secret"))
        try {
            client.health()
            org.junit.Assert.fail("Invalid JSON accepted")
        } catch (e: OpenCodeApiException) {
            assertTrue(e.message!!.contains("/global/health"))
            assertTrue(!e.message!!.contains("secret"))
        }
    }

    @Test
    fun `json primitive helper parses events`() {
        val event = OcClientJson.decodeEvent("""{"type":"server.connected","properties":{"x":1}}""")
        assertEquals("server.connected", event.type)
    }
}

/** Small indirection so the test exercises the same Json used in production. */
object OcClientJson {
    private val json = io.github.oustery.opencodephone.core.model.OcJson.lenient
    fun decodeEvent(raw: String) = json.decodeFromString(
        io.github.oustery.opencodephone.core.model.ServerEvent.serializer(),
        raw,
    )
}
