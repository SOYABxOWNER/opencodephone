package io.github.oustery.opencodephone.ui.update

import org.junit.Assert.*
import org.junit.Test

class ReleasePolicyTest {
    private fun release(abi: String = "arm64-v8a", url: String? = null, size: Long = 123, pre: Boolean = false): String {
        val path = url ?: "https://github.com/oustery/opencodephone/releases/download/v0.2.0/OpenCodePhone-v0.2.0-$abi.apk"
        return """{"draft":false,"prerelease":$pre,"tag_name":"v0.2.0","assets":[{"name":"OpenCodePhone-v0.2.0-$abi.apk","browser_download_url":"$path","size":$size,"digest":null}]}"""
    }

    @Test fun `numeric version comparison rejects old equal and unstable tags`() {
        assertTrue(ReleasePolicy.newer("v0.10.0", "0.2.9"))
        assertFalse(ReleasePolicy.newer("v0.1.3", "0.1.3"))
        assertFalse(ReleasePolicy.newer("v0.1.2", "0.1.3"))
        assertFalse(ReleasePolicy.newer("v1.0.0-beta", "0.1.3"))
        assertFalse(ReleasePolicy.newer("v999999999999999999999.0.0", "0.1.3"))
    }

    @Test fun `selects compatible exact ABI and ignores current release`() {
        assertEquals("0.2.0", ReleasePolicy.select(release(), "0.1.3", listOf("arm64-v8a"))!!.version)
        assertEquals("0.2.0", ReleasePolicy.select(release("armeabi-v7a"), "0.1.3", listOf("arm64-v8a", "armeabi-v7a"))!!.version)
        assertNull(ReleasePolicy.select(release(), "0.2.0", listOf("arm64-v8a")))
        assertThrows(IllegalStateException::class.java) { ReleasePolicy.select(release(), "0.1.3", listOf("x86_64")) }
    }

    @Test fun `rejects untrusted URLs oversized assets and prereleases`() {
        for (json in listOf(release(url = "https://evil.example/a.apk"), release(size = ReleasePolicy.MAX_APK + 1), release(pre = true))) {
            assertThrows(IllegalStateException::class.java) { ReleasePolicy.select(json, "0.1.3", listOf("arm64-v8a")) }
        }
    }
}
