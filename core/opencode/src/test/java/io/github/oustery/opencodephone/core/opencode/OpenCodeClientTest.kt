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
            MockResponse().setBody("""{"healthy":true,"version":"2.2.6"}"""),
        )
        val health = client.health()
        assertEquals(true, health.healthy)
        assertEquals("2.2.6", health.version)
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
    fun `basic auth header is attached when password configured`() = runBlocking {
        client.config = client.config.copy(username = "opencode", password = "secret")
        server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"x"}"""))
        client.health()
        val recorded = server.takeRequest()
        assertNotNull(recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Authorization")!!.startsWith("Basic "))
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
