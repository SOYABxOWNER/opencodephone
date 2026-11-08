package io.github.oustery.opencodephone.core.settings

import kotlinx.coroutines.CancellationException
import java.security.SecureRandom
import java.util.Base64

class SecureSettingsUnavailableException(cause: Exception) :
    Exception("Secure settings unavailable; existing data has not been deleted", cause)

/** No alternative plaintext store is ever opened if the secure store fails. */
internal fun <T> openSecureSettings(open: () -> T): T = try {
    open()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw SecureSettingsUnavailableException(e)
}

/** A local listener secret is independent of user-entered remote credentials. */
internal class PersistentLocalSecret(
    private val read: () -> String?,
    private val persist: (String) -> Boolean,
) {
    @Synchronized
    fun get(): String = openSecureSettings {
        read()?.takeIf { it.isNotBlank() } ?: run {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            check(persist(secret)) { "Cannot persist local server secret" }
            secret
        }
    }
}

internal fun effectiveServerConfig(config: ServerConfig, localSecret: () -> String): ServerConfig =
    if (config.mode == ServerMode.LOCAL) {
        config.copy(host = "127.0.0.1", username = "opencode", password = localSecret())
    } else config
