package io.github.oustery.opencodephone.core.linuxenv

import org.junit.Assert.*
import org.junit.Test

class LocalServerAuthTest {
    @Test fun `blank secret cannot produce an unauthenticated launch environment`() {
        for (password in listOf(null, "", "  ")) {
            assertThrows(IllegalArgumentException::class.java) {
                localServerEnvironment(ServerLaunchConfig(4096, "opencode", password))
            }
        }
    }

    @Test fun `launcher receives the same generated secret as the client configuration`() {
        val env = localServerEnvironment(ServerLaunchConfig(4096, null, "generated-test-secret"))
        assertEquals("generated-test-secret", env["OPENCODE_SERVER_PASSWORD"])
        assertEquals("opencode", env["OPENCODE_SERVER_USERNAME"])
    }
}
