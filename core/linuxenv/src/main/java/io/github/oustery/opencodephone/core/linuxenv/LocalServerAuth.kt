package io.github.oustery.opencodephone.core.linuxenv

/** The managed local listener must never start with authentication disabled. */
internal fun localServerEnvironment(config: ServerLaunchConfig): Map<String, String> {
    val password = config.password
    require(!password.isNullOrBlank()) { "A local server secret is required" }
    return mapOf(
        "OPENCODE_SERVER_PASSWORD" to password,
        "OPENCODE_SERVER_USERNAME" to (config.username?.ifBlank { "opencode" } ?: "opencode"),
    )
}
