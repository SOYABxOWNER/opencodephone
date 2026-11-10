package io.github.oustery.opencodephone.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.oustery.opencodephone.core.model.Pins

enum class ServerMode { LOCAL, REMOTE }

/**
 * First-run onboarding path (functional audit F-022).
 * UNSET means the choice screen is still pending; REMOTE_ONLY skips the
 * heavy Debian download until the user asks for the local environment.
 */
enum class SetupChoice { UNSET, LOCAL, REMOTE_ONLY }

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
 * The OpenCode console account signed in from the app (device flow).
 * The access token doubles as the `opencode` (Zen) provider API key pushed
 * to the server by the regular provider-key sync; secrets never leave the
 * encrypted store and are excluded from the settings backup.
 */
data class OpenCodeAccount(
    val accountId: String,
    val email: String,
    val consoleUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val orgId: String? = null,
    val orgName: String? = null,
)

/**
 * Encrypted settings storage (Android Keystore-backed).
 * Fails closed if Keystore cannot be opened. Startup offers retry without
 * deleting settings; credentials are never written to a plaintext fallback.
 */
class SettingsStore(private val context: Context) {

    private val prefs: SharedPreferences = openSecureSettings {
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
    }

    // Detect legacy data without loading it or silently preferring stale plaintext values.
    val hasLegacyPlaintextSettings: Boolean get() = java.io.File(
        context.applicationInfo.dataDir, "shared_prefs/opencodephone_fallback.xml",
    ).exists()

    /** Explicit, user-confirmed import. Existing secure values win; secrets are never exported. */
    fun importLegacySettings() {
        val old = context.getSharedPreferences("opencodephone_fallback", Context.MODE_PRIVATE)
        val allowed = setOf(KEY_SERVER_MODE, KEY_SERVER_HOST, KEY_SERVER_PORT, KEY_SERVER_USERNAME,
            KEY_SERVER_PASSWORD, KEY_PROVIDER_KEYS, KEY_MODEL_PROVIDER, KEY_MODEL_ID,
            KEY_GIT_NAME, KEY_GIT_EMAIL, KEY_GIT_TOKEN, KEY_OPENCODE_PIN, KEY_DYNAMIC_COLOR)
        val editor = prefs.edit()
        for ((key, value) in old.all) if (key in allowed && !prefs.contains(key)) {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
            }
        }
        check(editor.commit()) { "Secure import failed; legacy settings retained" }
        check(old.edit().clear().commit()) { "Secure import saved; legacy cleanup must be retried" }
        check(context.deleteSharedPreferences("opencodephone_fallback")) { "Legacy cleanup must be retried" }
    }

    private fun endpoint(config: ServerConfig): String = if (config.mode == ServerMode.LOCAL) "local"
        else "https:${config.host.lowercase()}:${config.port}:${config.username}"
    fun providerSyncAllowed(config: ServerConfig): Boolean = config.mode == ServerMode.LOCAL ||
        prefs.getBoolean("consent:${endpoint(config)}", false)
    fun setProviderSyncAllowed(config: ServerConfig, allowed: Boolean) = edit {
        it.putBoolean("consent:${endpoint(config)}", allowed)
    }
    fun managedProviders(config: ServerConfig): Set<String> =
        prefs.getStringSet("managed:${endpoint(config)}", emptySet())?.toSet() ?: emptySet()
    fun recordManagedProviders(config: ServerConfig, providers: Set<String>) {
        check(prefs.edit().putStringSet("managed:${endpoint(config)}", providers).commit())
    }

    private val localSecret = PersistentLocalSecret(
        read = { prefs.getString(KEY_LOCAL_SERVER_SECRET, null) },
        persist = { prefs.edit().putString(KEY_LOCAL_SERVER_SECRET, it).commit() },
    )

    /** The local API never uses a blank password or reuses a remote credential. */
    fun effectiveServerConfig(): ServerConfig = effectiveServerConfig(serverConfig(), localSecret::get)

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

    // --------------------------------------------------- OpenCode account

    fun opencodeAccount(): OpenCodeAccount? {
        val json = prefs.getString(KEY_OPENCODE_ACCOUNT, null)?.ifBlank { null } ?: return null
        return runCatching {
            val obj = org.json.JSONObject(json)
            OpenCodeAccount(
                accountId = obj.optString("accountId"),
                email = obj.optString("email"),
                consoleUrl = obj.optString("consoleUrl", OpenCodeConsoleUrl.DEFAULT),
                accessToken = obj.optString("accessToken"),
                refreshToken = obj.optString("refreshToken"),
                expiresAtMs = obj.optLong("expiresAtMs"),
                orgId = obj.optString("orgId").ifBlank { null },
                orgName = obj.optString("orgName").ifBlank { null },
            )
        }.getOrNull()?.takeIf { it.accountId.isNotBlank() && it.accessToken.isNotBlank() }
    }

    fun setOpencodeAccount(account: OpenCodeAccount?) = edit {
        if (account == null) {
            it.remove(KEY_OPENCODE_ACCOUNT)
        } else {
            it.putString(
                KEY_OPENCODE_ACCOUNT,
                org.json.JSONObject()
                    .put("accountId", account.accountId)
                    .put("email", account.email)
                    .put("consoleUrl", account.consoleUrl)
                    .put("accessToken", account.accessToken)
                    .put("refreshToken", account.refreshToken)
                    .put("expiresAtMs", account.expiresAtMs)
                    .put("orgId", account.orgId.orEmpty())
                    .put("orgName", account.orgName.orEmpty())
                    .toString(),
            )
        }
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

    // ------------------------------------------------------------- Agents

    /**
     * Selected opencode agent (`build`, `plan`, …); null follows the server
     * default (functional audit F-006). Persisted so the choice survives
     * restarts, mirroring the model selection.
     */
    fun agentName(): String? = prefs.getString(KEY_AGENT_NAME, null)?.ifBlank { null }

    fun setAgentName(name: String?) = edit {
        if (name.isNullOrBlank()) it.remove(KEY_AGENT_NAME) else it.putString(KEY_AGENT_NAME, name)
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
        (prefs.getString(KEY_OPENCODE_PIN, Pins.OPENCODE_VERSION) ?: Pins.OPENCODE_VERSION)
            // Migrate the incompatible default shipped before 0.1.3.
            .let { if (it == "2.2.6") Pins.OPENCODE_VERSION else it }

    fun setOpencodeVersionPin(version: String) = edit {
        it.putString(KEY_OPENCODE_PIN, version)
    }

    fun hasCompletedBootstrap(): Boolean = prefs.getBoolean(KEY_BOOTSTRAP_DONE, false)

    fun setBootstrapCompleted() = edit { it.putBoolean(KEY_BOOTSTRAP_DONE, true) }

    // ---------------------------------------------------------- Onboarding

    /** Remote-first onboarding choice (F-022); persists across restarts. */
    fun setupChoice(): SetupChoice = runCatching {
        SetupChoice.valueOf(prefs.getString(KEY_SETUP_CHOICE, SetupChoice.UNSET.name) ?: SetupChoice.UNSET.name)
    }.getOrDefault(SetupChoice.UNSET)

    fun setSetupChoice(choice: SetupChoice) = edit { it.putString(KEY_SETUP_CHOICE, choice.name) }

    // ------------------------------------------------------ Project context

    /**
     * Workspace-relative path of the repository selected in Files; the shared
     * working context for chat session titles and the terminal cwd (F-001).
     * Empty means the workspace root.
     */
    fun selectedProject(): String? = prefs.getString(KEY_SELECTED_PROJECT, null)?.ifBlank { null }

    fun setSelectedProject(relativePath: String?) = edit {
        it.putString(KEY_SELECTED_PROJECT, relativePath.orEmpty())
    }

    // -------------------------------------------------------- Appearance

    /** Expressive toggle for Material You dynamic color on Android 12+ (M3E audit E-016). */
    fun dynamicColor(): Boolean = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)

    fun setDynamicColor(enabled: Boolean) = edit { it.putBoolean(KEY_DYNAMIC_COLOR, enabled) }

    // ---------------------------------------------------------- Backup

    /**
     * User-facing settings backup (functional audit F-013). Only non-secret
     * values are exported: server address without the password, Git identity
     * without the token, model selection, appearance, onboarding choice and
     * the selected project. Secrets never leave the encrypted store.
     */
    fun exportNonSecret(): org.json.JSONObject {
        val server = serverConfig()
        val git = gitConfig()
        val model = modelSelection()
        return org.json.JSONObject()
            .put("schema", 1)
            .put(
                "server",
                org.json.JSONObject()
                    .put("mode", server.mode.name)
                    .put("host", server.host)
                    .put("port", server.port)
                    .put("username", server.username),
            )
            .put(
                "git",
                org.json.JSONObject()
                    .put("userName", git.userName)
                    .put("userEmail", git.userEmail),
            )
            .put(
                "model",
                org.json.JSONObject()
                    .put("providerId", model.providerId)
                    .put("modelId", model.modelId),
            )
            .put("agent", agentName().orEmpty())
            .put("opencodePin", opencodeVersionPin())
            .put("dynamicColor", dynamicColor())
            .put("setupChoice", setupChoice().name)
            .put("selectedProject", selectedProject().orEmpty())
    }

    /**
     * Apply an exported snapshot. Returns the number of top-level sections
     * found; unknown fields are ignored and secrets (password, provider
     * keys, Git token) are untouched by construction.
     */
    fun importNonSecret(json: org.json.JSONObject): Int {
        var sections = 0
        if (json.has("server")) {
            val s = json.optJSONObject("server")
            if (s != null) {
                val mode = runCatching {
                    ServerMode.valueOf(s.optString("mode", ServerMode.LOCAL.name))
                }.getOrDefault(ServerMode.LOCAL)
                val port = s.optInt("port", 4096).coerceIn(1, 65535)
                setServerConfig(
                    ServerConfig(
                        mode = mode,
                        host = s.optString("host", "127.0.0.1").ifBlank { "127.0.0.1" },
                        port = port,
                        username = s.optString("username", "opencode").ifBlank { "opencode" },
                    ),
                )
                sections++
            }
        }
        if (json.has("git")) {
            val g = json.optJSONObject("git")
            if (g != null) {
                val current = gitConfig()
                setGitConfig(
                    GitConfig(
                        userName = g.optString("userName", current.userName),
                        userEmail = g.optString("userEmail", current.userEmail),
                        githubToken = current.githubToken,
                    ),
                )
                sections++
            }
        }
        if (json.has("model")) {
            val m = json.optJSONObject("model")
            if (m != null) {
                setModelSelection(ModelSelection(m.optString("providerId"), m.optString("modelId")))
                sections++
            }
        }
        if (json.has("agent")) setAgentName(json.optString("agent").ifBlank { null })
        if (json.has("opencodePin")) {
            val pin = json.optString("opencodePin", Pins.OPENCODE_VERSION)
            if (pin.isNotBlank()) setOpencodeVersionPin(pin)
        }
        if (json.has("dynamicColor")) setDynamicColor(json.optBoolean("dynamicColor", false))
        if (json.has("setupChoice")) {
            runCatching { setSetupChoice(SetupChoice.valueOf(json.getString("setupChoice"))) }
        }
        if (json.has("selectedProject")) setSelectedProject(json.optString("selectedProject").ifBlank { null })
        return sections
    }

    companion object {
        /** Destructive recovery must only be invoked after explicit UI confirmation. */
        fun resetEncryptedSettings(context: Context) {
            check(context.deleteSharedPreferences("opencodephone_secure")) { "Cannot remove encrypted settings" }
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }

        private const val KEY_LOCAL_SERVER_SECRET = "local_server_secret"
        private const val KEY_SERVER_MODE = "server_mode"
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_SERVER_USERNAME = "server_username"
        private const val KEY_SERVER_PASSWORD = "server_password"
        private const val KEY_PROVIDER_KEYS = "provider_keys"
        private const val KEY_MODEL_PROVIDER = "model_provider"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_AGENT_NAME = "agent_name"
        private const val KEY_GIT_NAME = "git_user_name"
        private const val KEY_GIT_EMAIL = "git_user_email"
        private const val KEY_GIT_TOKEN = "git_github_token"
        private const val KEY_OPENCODE_PIN = "opencode_version_pin"
        private const val KEY_BOOTSTRAP_DONE = "bootstrap_completed"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_SETUP_CHOICE = "setup_choice"
        private const val KEY_SELECTED_PROJECT = "selected_project"
        private const val KEY_OPENCODE_ACCOUNT = "opencode_account"
    }
}

/** Default console URL, mirrored here for settings defaults. */
object OpenCodeConsoleUrl {
    const val DEFAULT = "https://opencode.ai/console"
}
