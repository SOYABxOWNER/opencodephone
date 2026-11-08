package io.github.oustery.opencodephone.core.linuxenv

import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class DockerRegistryTest {
    private fun digest(text: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun `fetch verifies exact manifest bytes before parsing`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = OkHttpClient()
            val registry = DockerRegistryClient(Call.Factory { request ->
                client.newCall(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            })
            val manifest = """{ "layers": [{"digest":"sha256:abc","size":1}] }"""
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setBody(manifest))
            val result = registry.fetchManifest(digest(manifest))
            assertEquals("sha256:abc", registry.singleLayer(result).digest)
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("Bearer test", request.getHeader("Authorization"))
            assertEquals("/v2/library/debian/manifests/${digest(manifest)}", request.path)

            // Same valid JSON, different byte representation: must not match the pin.
            server.enqueue(MockResponse().setBody("""{"token":"test"}"""))
            server.enqueue(MockResponse().setBody(manifest.replace(" ", "")))
            val failure = assertThrows(IllegalStateException::class.java) {
                runBlocking { registry.fetchManifest(digest(manifest)) }
            }
            assertEquals("Manifest digest mismatch", failure.message)
        }
    }

    @Test fun `invalid JSON is rejected for integrity before parsing`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            DockerRegistryClient.parseVerifiedManifest("<!doctype html>".toByteArray(), digest("{}"))
        }
        assertEquals("Manifest digest mismatch", failure.message)
    }
}
