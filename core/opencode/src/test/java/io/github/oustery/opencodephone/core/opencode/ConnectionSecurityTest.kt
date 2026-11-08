package io.github.oustery.opencodephone.core.opencode

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLException

class ConnectionSecurityTest {
    private val health = """{"healthy":true,"version":"1.19.5"}"""

    @Test fun `remote defaults to HTTPS and cleartext cannot be opted into`() {
        assertEquals("https://example.com:4096", ConnectionConfig("example.com").baseUrl)
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionConfig("example.com", scheme = "http", password = "secret").baseUrl
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionConfig("127.0.0.1.evil.example", scheme = "http").baseUrl
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionConfig("https://example.com").baseUrl
        }
    }

    @Test fun `loopback and IPv6 URLs are built correctly`() {
        assertEquals("http://127.0.0.1:4096", ConnectionConfig().baseUrl)
        assertEquals("http://localhost:4096", ConnectionConfig("localhost").baseUrl)
        assertEquals("http://[::1]:4096", ConnectionConfig("::1").baseUrl)
        assertEquals("https://[2001:db8::1]:4096", ConnectionConfig("2001:db8::1").baseUrl)
    }

    @Test fun `HTTPS validates peer and sends authentication only to selected endpoint`() = runBlocking {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val config = ConnectionConfig("localhost", server.port, password = "test-secret", scheme = "https")
            val client = OpenCodeClient(
                client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build(),
                config = config,
            )
            server.enqueue(MockResponse().setBody(health))
            assertTrue(client.health().healthy)
            assertEquals(Credentials.basic("opencode", "test-secret"), server.takeRequest().getHeader("Authorization"))

            // The default transport must NOT accept this self-signed test certificate.
            assertThrows(SSLException::class.java) {
                runBlocking { OpenCodeClient(config = config).health() }
            }
            Unit
        }
    }

    @Test fun `redirect cannot downgrade or forward provider key to a different endpoint`() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { source ->
            MockWebServer().use { plainTarget ->
                MockWebServer().use { secureTarget ->
                    source.useHttps(serverTls.sslSocketFactory(), false)
                    secureTarget.useHttps(serverTls.sslSocketFactory(), false)
                    source.start()
                    plainTarget.start()
                    secureTarget.start()
                    plainTarget.enqueue(MockResponse().setResponseCode(204))
                    secureTarget.enqueue(MockResponse().setResponseCode(204))
                    val client = OpenCodeClient(
                        client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build(),
                        config = ConnectionConfig("localhost", source.port, password = "test-secret", scheme = "https"),
                    )
                    for (target in listOf(plainTarget, secureTarget)) {
                        source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/auth/openai")))
                        val error = assertThrows(OpenCodeApiException::class.java) {
                            runBlocking { client.setProviderAuth("openai", client.apiCredentials("openai", "fake-key")) }
                        }
                        assertEquals(307, error.code)
                        assertEquals(0, target.requestCount)
                    }
                }
            }
        }
    }
}
