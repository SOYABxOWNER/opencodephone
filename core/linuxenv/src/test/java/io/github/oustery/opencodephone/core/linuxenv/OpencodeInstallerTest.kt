package io.github.oustery.opencodephone.core.linuxenv

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OpencodeInstallerTest {
    @Test
    fun `V1 package naming matches supported API and requires registry integrity`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val installer = OpencodeInstaller(OkHttpClient(), server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setBody("""{"dist":{"tarball":"https://registry.npmjs.org/example.tgz","integrity":"sha512-example"}}"""))
            val info = installer.resolvePackage("linux-arm64", "1.19.5")
            assertEquals("sha512-example", info.integrity)
            assertEquals("/opencode-linux-arm64/1.19.5", server.takeRequest().path)
            try {
                installer.resolvePackage("linux-arm64", "2.2.6")
                fail("Must not install incompatible V2")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("V1"))
            }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
