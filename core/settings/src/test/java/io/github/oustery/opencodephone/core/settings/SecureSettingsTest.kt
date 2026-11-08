package io.github.oustery.opencodephone.core.settings

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class SecureSettingsTest {
    @Test fun `local and remote credentials remain separate across mode changes`() {
        val stored = ServerConfig(host = "remote.example", username = "remote-user", password = "remote-key")
        val local = effectiveServerConfig(stored) { "local-secret" }
        assertEquals("local-secret", local.password)
        assertEquals("opencode", local.username)
        assertEquals("127.0.0.1", local.host)
        val remote = stored.copy(mode = ServerMode.REMOTE)
        assertEquals(remote, effectiveServerConfig(remote) { error("must not access local key") })
        assertEquals("remote-key", stored.password)
    }

    @Test fun `failed secure open preserves cause and never returns an alternative store`() {
        val cause = IllegalStateException("Keystore unavailable")
        val failure = assertThrows(SecureSettingsUnavailableException::class.java) {
            openSecureSettings<Any> { throw cause }
        }
        assertSame(cause, failure.cause)
    }

    @Test fun `cancellation is not wrapped as a storage failure`() {
        val cancellation = CancellationException("cancelled")
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            openSecureSettings<Any> { throw cancellation }
        })
    }

    @Test fun `local secret has 256 random bits and survives reopen`() {
        var stored: String? = null
        var writes = 0
        val secret = PersistentLocalSecret({ stored }, { stored = it; writes++; true })
        val value = secret.get()
        assertEquals(32, Base64.getUrlDecoder().decode(value).size)
        assertEquals(value, secret.get())
        assertEquals(value, PersistentLocalSecret({ stored }, { error("must not rewrite") }).get())
        assertEquals(1, writes)
        val another = PersistentLocalSecret({ null }, { true }).get()
        assertNotEquals(value, another)
    }

    @Test fun `concurrent callers persist exactly one secret`() {
        var stored: String? = null
        var writes = 0
        val secret = PersistentLocalSecret({ stored }, { stored = it; writes++; true })
        val pool = Executors.newFixedThreadPool(8)
        try {
            val values = pool.invokeAll(List(50) { Callable { secret.get() } }).map { it.get() }
            assertEquals(1, values.toSet().size)
            assertEquals(1, writes)
        } finally { pool.shutdownNow() }
    }

    @Test fun `failed persistence does not expose an ephemeral unauthenticated configuration`() {
        assertThrows(SecureSettingsUnavailableException::class.java) {
            PersistentLocalSecret({ null }, { false }).get()
        }
    }
}
