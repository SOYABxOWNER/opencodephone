package io.github.oustery.opencodephone.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.oustery.opencodephone.core.model.Pins

enum class ServerMode { LOCAL, REMOTE }

data class ServerConfig(
    val mode: ServerMode = ServerMode.LOCAL,
    val host: String = "127.0.0.1",
    val port: Int = 4096,
    val username: String = "opencode",
    val password: String = "",
)

data class ProviderKey(
    val providerId: String,
    val apiKey: String,
)

data class GitConfig(
    val userName: String = "",
    val userEmail: String = "",
    val githubToken: String = "",
)

data class ModelSelection(
    val providerId: String = "",
    val modelId: String = "",
)

/**
 * Encrypted settings storage (Android Keystore-backed).
 * Falls back to plain preferences if the hardware keystore is unavailable —
 * acceptable for a development tool, documented in ADR-003.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "opencodephone_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        context.getSharedPreferences("opencodephone_fallback", Context.MODE_PRIVATE)
    }

    private fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val editor = prefs.edit()
        block(editor)
        editor.apply()
    }

    // ------------------------------------------------------------- Server

    fun serverConfig(): ServerConfig = ServerConfig(
        mode = runCatching {
            ServerMode.valueOf(prefs.getString(KEY_SERVER_MODE, ServerMode.LOCAL.name) ?: ServerMode.LOCAL.name)
        }.getOrDefault(ServerMode.LOCAL),
        host = prefs.getString(KEY_SERVER_HOST, "127.0.0.1") ?: "127.0.0.1",
        port = prefs.getInt(KEY_SERVER_PORT, 4096),
        username = prefs.getString(KEY_SERVER_USERNAME, "opencode") ?: "opencode",
        password = prefs.getString(KEY_SERVER_PASSWORD, "") ?: "",
    )

    fun setServerConfig(config: ServerConfig) = edit {
        it.putString(KEY_SERVER_MODE, config.mode.name)
        it.putString(KEY_SERVER_HOST, config.host)
        it.putInt(KEY_SERVER_PORT, config.port)
        it.putString(KEY_SERVER_USERNAME, config.username)
        it.putString(KEY_SERVER_PASSWORD, config.password)
    }

    // ------------------------------------------------------ AI providers

    fun providerKeys(): List<ProviderKey> =
        ProviderKeysCodec.decode(prefs.getString(KEY_PROVIDER_KEYS, "{}") ?: "{}")
            .map { (id, key) -> ProviderKey(id, key) }

    fun setProviderKey(providerId: String, apiKey: String) {
        val current = ProviderKeysCodec.decode(prefs.getString(KEY_PROVIDER_KEYS, "{}") ?: "{}")
        val updated = current + (providerId to apiKey)
        edit { it.putString(KEY_PROVIDER_KEYS, ProviderKeysCodec.encode(updated)) }
    }

    fun removeProviderKey(providerId: String) {
        val current = ProviderKeysCodec.decode(prefs.getString(KEY_PROVIDER_KEYS, "{}") ?: "{}")
        val updated = current - providerId
        edit { it.putString(KEY_PROVIDER_KEYS, ProviderKeysCodec.encode(updated)) }
    }

    // ------------------------------------------------------------- Models

    fun modelSelection(): ModelSelection = ModelSelection(
        providerId = prefs.getString(KEY_MODEL_PROVIDER, "") ?: "",
        modelId = prefs.getString(KEY_MODEL_ID, "") ?: "",
    )

    fun setModelSelection(selection: ModelSelection) = edit {
        it.putString(KEY_MODEL_PROVIDER, selection.providerId)
        it.putString(KEY_MODEL_ID, selection.modelId)
    }

    // ---------------------------------------------------------------- Git

    fun gitConfig(): GitConfig = GitConfig(
        userName = prefs.getString(KEY_GIT_NAME, "") ?: "",
        userEmail = prefs.getString(KEY_GIT_EMAIL, "") ?: "",
        githubToken = prefs.getString(KEY_GIT_TOKEN, "") ?: "",
    )

    fun setGitConfig(config: GitConfig) = edit {
        it.putString(KEY_GIT_NAME, config.userName)
        it.putString(KEY_GIT_EMAIL, config.userEmail)
        it.putString(KEY_GIT_TOKEN, config.githubToken)
    }

    // ---------------------------------------------------------- Versions

    fun opencodeVersionPin(): String =
        prefs.getString(KEY_OPENCODE_PIN, Pins.OPENCODE_VERSION) ?: Pins.OPENCODE_VERSION

    fun setOpencodeVersionPin(version: String) = edit {
        it.putString(KEY_OPENCODE_PIN, version)
    }

    fun hasCompletedBootstrap(): Boolean = prefs.getBoolean(KEY_BOOTSTRAP_DONE, false)

    fun setBootstrapCompleted() = edit { it.putBoolean(KEY_BOOTSTRAP_DONE, true) }

    private companion object {
        const val KEY_SERVER_MODE = "server_mode"
        const val KEY_SERVER_HOST = "server_host"
        const val KEY_SERVER_PORT = "server_port"
        const val KEY_SERVER_USERNAME = "server_username"
        const val KEY_SERVER_PASSWORD = "server_password"
        const val KEY_PROVIDER_KEYS = "provider_keys"
        const val KEY_MODEL_PROVIDER = "model_provider"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_GIT_NAME = "git_user_name"
        const val KEY_GIT_EMAIL = "git_user_email"
        const val KEY_GIT_TOKEN = "git_github_token"
        const val KEY_OPENCODE_PIN = "opencode_version_pin"
        const val KEY_BOOTSTRAP_DONE = "bootstrap_completed"
    }
}
