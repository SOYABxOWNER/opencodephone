package io.github.oustery.opencodephone.core.opencode

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
