package io.github.oustery.opencodephone.core.opencode

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OpenCodeConsoleClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenCodeConsoleClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenCodeConsoleClient(baseUrl = server.url("/console").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * The live console returns the verification page relative to its own
     * origin (`/console/device?user_code=…`). A scheme-less path fed to
     * `ACTION_VIEW` is not handled by any browser and device sign-in died
     * with the "no browser" message — the client must resolve it against
     * the console base URL before the URL reaches the UI.
     */
    @Test
    fun `resolves a relative verification url against the console origin`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"device_code":"dev_1","user_code":"AB12-CD34",
                    "verification_uri_complete":"/console/device?user_code=AB12-CD34&client_id=opencode-cli",
                    "verification_uri":"/console/device","expires_in":600,"interval":5}""",
            ),
        )
        val login = client.startLogin()
        assertEquals(
            "http://localhost:${server.port}/console/device?user_code=AB12-CD34&client_id=opencode-cli",
            login.verificationUrl,
        )
        assertEquals("/console/auth/device/code", server.takeRequest().path)
    }

    @Test
    fun `keeps an absolute verification url unchanged`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"device_code":"dev_2","user_code":"ZZ99-XX00",
                    "verification_uri_complete":"https://opencode.ai/console/device?user_code=ZZ99-XX00",
                    "expires_in":600,"interval":5}""",
            ),
        )
        val login = client.startLogin()
        assertEquals(
            "https://opencode.ai/console/device?user_code=ZZ99-XX00",
            login.verificationUrl,
        )
    }

    @Test
    fun `falls back to verification_uri when complete is missing`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"device_code":"dev_3","user_code":"QQ55-MM22",
                    "verification_uri":"/console/device","expires_in":600,"interval":5}""",
            ),
        )
        val login = client.startLogin()
        assertEquals("http://localhost:${server.port}/console/device", login.verificationUrl)
    }

    @Test
    fun `poll reports pending then success`() = runBlocking {
        val login = ConsoleLogin("dev_4", "UU11-2233", "http://localhost:1/device", 0L, 1_000L)
        server.enqueue(
            MockResponse().setBody("""{"error":"authorization_pending"}""").setResponseCode(400),
        )
        assertEquals(ConsolePollResult.Pending, client.poll(login))
        server.enqueue(
            MockResponse().setBody("""{"access_token":"at","refresh_token":"rt","expires_in":3600}"""),
        )
        val success = client.poll(login) as ConsolePollResult.Success
        assertEquals("at", success.accessToken)
        assertEquals("rt", success.refreshToken)
    }
}
