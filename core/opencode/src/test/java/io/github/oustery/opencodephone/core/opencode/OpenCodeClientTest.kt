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
        assertTrue("text mime", Attachments.isInlineable("text/plain", "notes.txt"))
        assertTrue("json mime", Attachments.isInlineable("application/json", "package.json"))
        assertTrue("code ext", Attachments.isInlineable("", "MainActivity.kt"))
        assertTrue("dotfile", Attachments.isInlineable("application/octet-stream", ".gitignore"))
        assertFalse("png", Attachments.isInlineable("image/png", "photo.png"))
        assertFalse("pdf", Attachments.isInlineable("application/pdf", "spec.pdf"))
        assertFalse("zip", Attachments.isInlineable("", "archive.zip"))
        val block = Attachments.inlineBlock("notes.md", "hello")
        // The fence language is the file EXTENSION ("md"), not the full name.
        assertTrue("block=[$block]", block.startsWith("Attached file \"notes.md\":\n```md\n"))
        assertTrue("block=[$block]", block.endsWith("```"))
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
    fun `session todos parse the pinned Todo shape`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"content":"Scaffold the page","status":"in_progress","priority":"high"},
                    {"content":"Add tests","status":"pending","priority":"medium"},
                    {"content":"Ship it","status":"completed","priority":"low"}]""",
            ),
        )
        val todos = client.sessionTodos("ses_1")
        assertEquals(3, todos.size)
        assertEquals("Scaffold the page", todos[0].content)
        assertEquals("in_progress", todos[0].status)
        assertEquals("high", todos[0].priority)
        assertEquals("completed", todos[2].status)
        assertEquals("/session/ses_1/todo", server.takeRequest().path)
    }

    @Test
    fun `session todos tolerate an empty or malformed body`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(0, client.sessionTodos("ses_1").size)
        server.enqueue(MockResponse().setBody("garbage"))
        assertEquals(0, client.sessionTodos("ses_1").size)
    }

    @Test
    fun `revert posts the messageID body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        client.revert("ses_1", "msg_42")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/session/ses_1/revert", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"messageID\":\"msg_42\""))
    }

    @Test
    fun `unrevert posts to the unrevert endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        client.unrevert("ses_1")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/session/ses_1/unrevert", recorded.path)
    }

    @Test
    fun `agents parse mode and hidden leniently`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"name":"build","mode":"primary"},
                    {"name":"plan","mode":"primary","hidden":false},
                    {"name":"explore","mode":"subagent"},
                    {"name":"internal","mode":"all","hidden":true},
                    {"name":"legacy"}]""",
            ),
        )
        val agents = client.listAgents()
        assertEquals(5, agents.size)
        assertEquals("primary", agents[0].mode)
        assertEquals(true, agents[3].hidden)
        assertEquals(null, agents[4].mode)
        assertEquals("/agent", server.takeRequest().path)
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

    @Test
    fun `permission reply uses the pinned v1 ReplyBody contract`() = runBlocking {
        // The v1.19.5 route validates `{reply: "once"|"always"|"reject"}`;
        // the earlier `{response, remember}` shape failed payload validation.
        server.enqueue(MockResponse().setResponseCode(200).setBody("true"))
        client.respondPermission("ses_1", "per_1", "once")
        val recorded = server.takeRequest()
        assertEquals("/session/ses_1/permissions/per_1", recorded.path)
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"reply\":\"once\""))
        assertFalse(body.contains("\"response\""))
        assertFalse(body.contains("\"remember\""))
    }

    @Test
    fun `parses pending question requests`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"que_1","sessionID":"ses_1","tool":{"messageID":"msg_1","callID":"call_1"},
                    "questions":[{"question":"Use which database?","header":"Database","multiple":false,
                    "options":[{"label":"SQLite","description":"embedded"},{"label":"Postgres","description":"server"}]}]}]""",
            ),
        )
        val requests = client.pendingQuestions()
        assertEquals(1, requests.size)
        assertEquals("que_1", requests[0].id)
        assertEquals("ses_1", requests[0].sessionId)
        assertEquals(1, requests[0].questions.size)
        assertEquals("Database", requests[0].questions[0].header)
        assertEquals(2, requests[0].questions[0].options.size)
        assertEquals("SQLite", requests[0].questions[0].options[0].label)
        assertEquals("embedded", requests[0].questions[0].options[0].description)
        val recorded = server.takeRequest()
        assertEquals("/question", recorded.path)
    }

    @Test
    fun `question reply sends ordered label arrays`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("true"))
        client.replyQuestion("que_1", listOf(listOf("SQLite"), listOf("A", "B")))
        val recorded = server.takeRequest()
        assertEquals("/question/que_1/reply", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("""{"answers":[["SQLite"],["A","B"]]}""", recorded.body.readUtf8())
    }

    @Test
    fun `question reject posts to the reject route`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("true"))
        client.rejectQuestion("que_2")
        val recorded = server.takeRequest()
        assertEquals("/question/que_2/reject", recorded.path)
        assertEquals("POST", recorded.method)
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
