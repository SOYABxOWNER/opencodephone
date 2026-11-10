package io.github.oustery.opencodephone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.R
import io.github.oustery.opencodephone.core.linuxenv.ServerState
import io.github.oustery.opencodephone.core.model.BootstrapState
import io.github.oustery.opencodephone.core.opencode.ConsolePollResult
import io.github.oustery.opencodephone.core.opencode.OpenCodeClient
import io.github.oustery.opencodephone.core.opencode.OpenCodeConsoleClient
import io.github.oustery.opencodephone.core.model.ProviderAuthMethod
import io.github.oustery.opencodephone.core.model.ProviderAuthPrompt
import io.github.oustery.opencodephone.core.settings.GitConfig
import io.github.oustery.opencodephone.core.settings.OpenCodeAccount
import io.github.oustery.opencodephone.core.settings.ProviderKey
import io.github.oustery.opencodephone.core.settings.ServerConfig
import io.github.oustery.opencodephone.core.settings.ServerMode
import io.github.oustery.opencodephone.core.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SettingsUiState(
    val server: ServerConfig = ServerConfig(),
    val providers: List<ProviderKey> = emptyList(),
    val git: GitConfig = GitConfig(),
    val opencodePin: String = "",
    val serverPortText: String = "4096",
    val providerSyncAllowed: Boolean = false,
    val connectionTest: String? = null,
    val serverDirty: Boolean = false,
    val bootstrap: BootstrapState = BootstrapState(),
    val serverStateLabel: String = "",
    val serverError: String? = null,
    val serverLog: String = "",
    val legacyPlaintextSettings: Boolean = false,
    val showAddProvider: Boolean = false,
    val showReinstallConfirm: Boolean = false,
    val dynamicColor: Boolean = false,
    val showImportConfirm: Boolean = false,
    val dataMessage: String? = null,
    // OpenCode console account (device flow sign-in).
    val account: OpenCodeAccount? = null,
    val accountAuth: AccountAuthUi? = null,
    val accountMessage: String? = null,
    // Interactive provider OAuth methods reported by the server.
    val serverAuthMethods: Map<String, List<ProviderAuthMethod>> = emptyMap(),
    val providerOAuth: ProviderOAuthUi? = null,
    val providerMessage: String? = null,
)

/** Progress of the console device-flow sign-in shown in a dialog. */
sealed interface AccountAuthUi {
    /** The user must confirm the code in the browser; the VM keeps polling. */
    data class AwaitApproval(val userCode: String, val verificationUrl: String) : AccountAuthUi
    /** The flow ended without success; the dialog offers a retry. */
    data class Failed(val message: String) : AccountAuthUi
}

/** Progress of an interactive provider OAuth flow (v1.19.5 auth plugins). */
sealed interface ProviderOAuthUi {
    /** The method asks for its own inputs before the authorize call. */
    data class CollectInputs(
        val providerId: String,
        val methodIndex: Int,
        val label: String,
        val prompts: List<ProviderAuthPrompt>,
    ) : ProviderOAuthUi

    /** The browser redirect is open; `code` flows wait for the pasted code. */
    data class AwaitBrowser(
        val providerId: String,
        val methodIndex: Int,
        val url: String,
        /** `auto` completes in the browser; `code` needs the pasted code. */
        val completion: String,
        val instructions: String,
    ) : ProviderOAuthUi

    /** The flow ended without success; the dialog offers a retry. */
    data class Failed(val providerId: String, val message: String) : ProviderOAuthUi
}

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {

    private val settings: SettingsStore = graph.settings

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        reload()
        viewModelScope.launch {
            graph.bootstrap.state.collect { bootstrap ->
                _state.update { it.copy(bootstrap = bootstrap) }
            }
        }
        viewModelScope.launch {
            graph.runtime.serverState.collect { serverState ->
                _state.update {
                    it.copy(
                        serverError = (serverState as? ServerState.Failed)?.message,
                        serverStateLabel = when (serverState) {
                            is ServerState.Running -> "opencode serve :${serverState.port}"
                            is ServerState.Starting -> "starting…"
                            is ServerState.Failed -> "failed"
                            ServerState.Stopped -> "stopped"
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            graph.runtime.serverLog.collect { log -> _state.update { it.copy(serverLog = log) } }
        }
        viewModelScope.launch {
            graph.dynamicColor.collect { enabled -> _state.update { it.copy(dynamicColor = enabled) } }
        }
    }

    fun restartServer() { if (!_state.value.serverDirty) graph.restartLocalServer() }

    fun reload() {
        _state.update {
            it.copy(
                serverDirty = false,
                legacyPlaintextSettings = settings.hasLegacyPlaintextSettings,
                server = settings.serverConfig(),
                serverPortText = settings.serverConfig().port.toString(),
                providerSyncAllowed = settings.providerSyncAllowed(settings.serverConfig()),
                providers = settings.providerKeys(),
                git = settings.gitConfig(),
                opencodePin = settings.opencodeVersionPin(),
                account = settings.opencodeAccount(),
            )
        }
    }

    // ------------------------------------------------------------- Server

    fun updateServer(transform: (ServerConfig) -> ServerConfig) {
        _state.update { it.copy(serverDirty = true, server = transform(it.server), providerSyncAllowed = false) }
    }
    fun setMode(mode: ServerMode) = updateServer { it.copy(mode = mode) }
    fun setHost(value: String) = updateServer { it.copy(host = value.trim()) }
    // Digits only, capped at a 5-char port (65535) — the digits IME is a
    // soft suggestion, hardware keyboards and paste still need the filter
    // (UI audit A5).
    fun setPort(value: String) = _state.update {
        it.copy(serverDirty = true, serverPortText = value.filter { c -> c.isDigit() }.take(5), providerSyncAllowed = false)
    }
    fun setUsername(value: String) = updateServer { it.copy(username = value.trim()) }
    fun setPassword(value: String) = updateServer { it.copy(password = value) }
    fun setProviderSyncAllowed(value: Boolean) = _state.update { it.copy(serverDirty = true, providerSyncAllowed = value) }

    fun applyServer() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val state = _state.value
                val port = state.serverPortText.toIntOrNull()
                require(port != null && port in 1..65535) { "Port must be between 1 and 65535" }
                val cfg = state.server.copy(port = port)
                if (cfg.mode == ServerMode.REMOTE) {
                    io.github.oustery.opencodephone.core.opencode.ConnectionConfig(cfg.host, port).baseUrl
                }
                settings.setServerConfig(cfg)
                settings.setProviderSyncAllowed(cfg, state.providerSyncAllowed)
                graph.refreshClient()
                graph.credentialsChanged()
                _state.update {
                    val changed = it.server != state.server || it.serverPortText != state.serverPortText ||
                        it.providerSyncAllowed != state.providerSyncAllowed
                    it.copy(serverDirty = changed, server = if (changed) it.server else cfg, connectionTest = "Applied")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(connectionTest = e.message) } }
        }
    }
    fun cancelServerChanges() = reload()

    fun importLegacySettings() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                settings.importLegacySettings()
                graph.refreshClient()
                reload()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(connectionTest = e.message) } }
        }
    }

    fun testConnection() {
        if (_state.value.serverDirty) return
        _state.update { it.copy(connectionTest = "…") }
        viewModelScope.launch {
            val tempClient = OpenCodeClient(config = AppGraph.buildClientConfig(settings))
            try {
                if (settings.serverConfig().mode == ServerMode.LOCAL) {
                    graph.ensureLocalServer()
                    val localState = graph.runtime.serverState.value
                    check(localState is ServerState.Running) {
                        (localState as? ServerState.Failed)?.message ?: "Local server is not ready"
                    }
                }
                val health = tempClient.health()
                // Interactive provider sign-in (v1.19.5 auth plugins) is a
                // server-reported surface; fetch it while the connection is
                // verified, degrading silently when unavailable.
                val methods = try {
                    tempClient.providerAuthMethods()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyMap()
                }
                _state.update {
                    it.copy(
                        connectionTest = "OK — opencode ${health.version}",
                        serverAuthMethods = methods,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(connectionTest = "Failed: ${e.message?.take(500)}")
                }
            }
        }
    }

    // --------------------------------------------------------- Providers

    fun showAddProvider(show: Boolean) = _state.update { it.copy(showAddProvider = show) }

    fun saveProviderKey(providerId: String, apiKey: String) {
        if (providerId.isBlank() || apiKey.isBlank()) return
        settings.setProviderKey(providerId.trim().lowercase(), apiKey.trim())
        graph.credentialsChanged()
        reload()
        _state.update { it.copy(showAddProvider = false) }
    }

    fun removeProviderKey(providerId: String) {
        settings.removeProviderKey(providerId)
        graph.credentialsChanged()
        reload()
    }

    // ----------------------------------------------- OpenCode account

    private var accountJob: Job? = null

    /**
     * Device-flow sign-in against the OpenCode console: request the device
     * code, show it, then poll until the user approves in the browser. The
     * resulting access token is stored in the encrypted settings and pushed
     * to the server as the `opencode` (Zen) provider key through the regular
     * provider sync.
     */
    fun startConsoleSignIn() {
        if (_state.value.accountAuth is AccountAuthUi.AwaitApproval) return
        accountJob?.cancel()
        _state.update { it.copy(accountAuth = null, accountMessage = null) }
        accountJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val login = graph.consoleClient.startLogin()
                _state.update {
                    it.copy(accountAuth = AccountAuthUi.AwaitApproval(login.userCode, login.verificationUrl))
                }
                var waitMs = login.intervalMs.coerceAtLeast(1_000L)
                while (isActive && System.currentTimeMillis() < login.expiresAtMs) {
                    delay(waitMs)
                    when (val result = graph.consoleClient.poll(login)) {
                        is ConsolePollResult.Pending -> {}
                        is ConsolePollResult.SlowDown -> waitMs += 5_000L
                        is ConsolePollResult.Success -> {
                            finishConsoleSignIn(result)
                            return@launch
                        }
                        is ConsolePollResult.Expired -> {
                            failConsoleSignIn(graph.appContext.getString(R.string.settings_account_expired))
                            return@launch
                        }
                        is ConsolePollResult.Denied -> {
                            failConsoleSignIn(graph.appContext.getString(R.string.settings_account_denied))
                            return@launch
                        }
                        is ConsolePollResult.Failure -> {
                            failConsoleSignIn(result.message)
                            return@launch
                        }
                    }
                }
                failConsoleSignIn(graph.appContext.getString(R.string.settings_account_expired))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failConsoleSignIn(e.message ?: "Sign-in failed")
            }
        }
    }

    private suspend fun finishConsoleSignIn(success: ConsolePollResult.Success) {
        val context = graph.appContext
        val user = graph.consoleClient.user(success.accessToken)
        val orgs = graph.consoleClient.orgs(success.accessToken)
        val account = OpenCodeAccount(
            accountId = user.id,
            email = user.email,
            consoleUrl = OpenCodeConsoleClient.DEFAULT_URL,
            accessToken = success.accessToken,
            refreshToken = success.refreshToken,
            expiresAtMs = System.currentTimeMillis() + success.expiresInMs,
            orgId = orgs.firstOrNull()?.id,
            orgName = orgs.firstOrNull()?.name,
        )
        settings.setOpencodeAccount(account)
        // The console token unlocks the `opencode` (Zen) provider models;
        // keep it in the encrypted key list so the regular provider sync
        // pushes it to the server on every chat connection.
        settings.setProviderKey(AppGraph.OPENCODE_ACCOUNT_PROVIDER_ID, success.accessToken)
        graph.credentialsChanged()
        reload()
        _state.update {
            it.copy(
                accountAuth = null,
                accountMessage = context.getString(R.string.settings_account_done, account.email),
            )
        }
    }

    private fun failConsoleSignIn(message: String) {
        _state.update { it.copy(accountAuth = AccountAuthUi.Failed(message)) }
    }

    /** Cancel an in-flight sign-in flow and close its dialog. */
    fun dismissAccountAuth() {
        accountJob?.cancel()
        accountJob = null
        _state.update { it.copy(accountAuth = null) }
    }

    /** Open the console verification URL in the system browser. */
    fun openVerificationUrl(url: String) {
        val context = graph.appContext
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            _state.update { it.copy(accountMessage = context.getString(R.string.settings_account_no_browser)) }
        }
    }

    /** Sign out: drop the stored account and the pushed `opencode` key. */
    fun signOutAccount() {
        accountJob?.cancel()
        accountJob = null
        settings.setOpencodeAccount(null)
        settings.removeProviderKey(AppGraph.OPENCODE_ACCOUNT_PROVIDER_ID)
        graph.credentialsChanged()
        reload()
    }

    // ------------------------------- Provider OAuth (server-side flows)

    private var oauthJob: Job? = null

    /**
     * Start the interactive sign-in for a provider. The first OAuth method
     * is used; if it declares prompts, the dialog collects them before the
     * authorize call. Methods are fetched on demand when the connection
     * test has not loaded them yet.
     */
    fun startProviderOAuth(providerId: String) {
        val cached = _state.value.serverAuthMethods[providerId]
        if (cached == null && _state.value.serverAuthMethods.isEmpty()) {
            oauthJob?.cancel()
            oauthJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val client = OpenCodeClient(config = AppGraph.buildClientConfig(settings))
                    val fresh = client.providerAuthMethods()
                    _state.update { it.copy(serverAuthMethods = fresh) }
                    startProviderOAuthWith(fresh[providerId], providerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failProviderOAuth(providerId, e.message ?: "Server unreachable")
                }
            }
            return
        }
        startProviderOAuthWith(cached, providerId)
    }

    private fun startProviderOAuthWith(methods: List<ProviderAuthMethod>?, providerId: String) {
        val index = methods.orEmpty().indexOfFirst { it.type == "oauth" }
        if (index < 0) {
            _state.update {
                it.copy(providerMessage = graph.appContext.getString(R.string.settings_providers_oauth_unavailable, providerId))
            }
            return
        }
        val method = methods.orEmpty()[index]
        if (method.prompts.isNotEmpty()) {
            _state.update {
                it.copy(providerOAuth = ProviderOAuthUi.CollectInputs(providerId, index, method.label, method.prompts))
            }
            return
        }
        authorizeProvider(providerId, index, emptyMap())
    }

    /** Submit the collected method inputs and start the authorize call. */
    fun submitProviderOAuthInputs(values: Map<String, String>) {
        val pending = _state.value.providerOAuth as? ProviderOAuthUi.CollectInputs ?: return
        _state.update { it.copy(providerOAuth = null) }
        authorizeProvider(pending.providerId, pending.methodIndex, values)
    }

    private fun authorizeProvider(providerId: String, methodIndex: Int, inputs: Map<String, String>) {
        oauthJob?.cancel()
        oauthJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = OpenCodeClient(config = AppGraph.buildClientConfig(settings))
                val authorization = client.providerOAuthAuthorize(providerId, methodIndex, inputs)
                if (authorization == null || authorization.url.isBlank()) {
                    // Credentials were stored server-side without a redirect.
                    finishProviderOAuth(providerId)
                    return@launch
                }
                _state.update {
                    it.copy(
                        providerOAuth = ProviderOAuthUi.AwaitBrowser(
                            providerId = providerId,
                            methodIndex = methodIndex,
                            url = authorization.url,
                            completion = authorization.method,
                            instructions = authorization.instructions,
                        ),
                    )
                }
                if (authorization.method != "code") {
                    // `auto` flows finish in the browser on their own; watch
                    // the connected list until the provider appears.
                    val deadline = System.currentTimeMillis() + 3 * 60_000L
                    while (isActive && System.currentTimeMillis() < deadline) {
                        delay(5_000L)
                        val connected = runCatching { client.listProviders().connected }.getOrDefault(emptyList())
                        if (providerId in connected) {
                            finishProviderOAuth(providerId)
                            return@launch
                        }
                    }
                    if (isActive) failProviderOAuth(providerId, graph.appContext.getString(R.string.settings_providers_oauth_timeout))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failProviderOAuth(providerId, e.message ?: "Sign-in failed")
            }
        }
    }

    /** Paste the browser-provided authorization code to finish a `code` flow. */
    fun submitProviderOAuthCode(code: String) {
        val pending = _state.value.providerOAuth as? ProviderOAuthUi.AwaitBrowser ?: return
        if (code.isBlank()) return
        oauthJob?.cancel()
        _state.update { it.copy(providerOAuth = null) }
        oauthJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = OpenCodeClient(config = AppGraph.buildClientConfig(settings))
                client.providerOAuthCallback(pending.providerId, pending.methodIndex, code.trim())
                finishProviderOAuth(pending.providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failProviderOAuth(pending.providerId, e.message ?: "Sign-in failed")
            }
        }
    }

    private suspend fun finishProviderOAuth(providerId: String) {
        val context = graph.appContext
        _state.update {
            it.copy(
                providerOAuth = null,
                providerMessage = context.getString(R.string.settings_providers_oauth_done, providerId),
            )
        }
    }

    private fun failProviderOAuth(providerId: String, message: String) {
        _state.update { it.copy(providerOAuth = ProviderOAuthUi.Failed(providerId, message)) }
    }

    fun openProviderOAuthUrl() {
        val pending = _state.value.providerOAuth as? ProviderOAuthUi.AwaitBrowser ?: return
        openVerificationUrl(pending.url)
    }

    /** Cancel an in-flight provider OAuth flow and close its dialog. */
    fun dismissProviderOAuth() {
        oauthJob?.cancel()
        oauthJob = null
        _state.update { it.copy(providerOAuth = null) }
    }

    // ---------------------------------------------------------------- Git

    fun setGitName(value: String) = persistGit { it.copy(userName = value.trim()) }

    fun setGitEmail(value: String) = persistGit { it.copy(userEmail = value.trim()) }

    fun setGitToken(value: String) = persistGit { it.copy(githubToken = value.trim()) }

    private fun persistGit(transform: (GitConfig) -> GitConfig) {
        val updated = transform(_state.value.git)
        settings.setGitConfig(updated)
        _state.update { it.copy(git = updated) }
    }

    // ---------------------------------------------------------- Versions

    fun setOpencodePin(value: String) {
        if (value.isBlank()) return
        settings.setOpencodeVersionPin(value.trim())
        _state.update { it.copy(opencodePin = value.trim()) }
    }

    // -------------------------------------------------------- Appearance

    /** Persisted through the graph so the live theme flow and the store stay in sync (E-016). */
    fun setDynamicColor(enabled: Boolean) = graph.setDynamicColor(enabled)

    // -------------------------------------------------------- Environment

    fun showReinstallConfirm(show: Boolean) = _state.update { it.copy(showReinstallConfirm = show) }

    /** Remote-first path (F-022): install the local environment on demand. */
    fun installLocalEnvironment() {
        graph.chooseSetup(io.github.oustery.opencodephone.core.settings.SetupChoice.LOCAL)
    }

    fun reinstallEnvironment() {
        _state.update { it.copy(showReinstallConfirm = false) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                graph.bootstrap.reset()
                graph.bootstrap.start(settings.opencodeVersionPin())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(connectionTest = e.message) } }
        }
    }

    fun workspacePath(): String = graph.layout.workspaceDir.absolutePath

    // ------------------------------------------------ Backup & data (F-013)

    private var pendingImport: org.json.JSONObject? = null

    /** Write the non-secret snapshot to a user-chosen SAF destination. */
    fun exportSettings(uri: android.net.Uri) {
        val context = graph.appContext
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val json = settings.exportNonSecret()
                val output = context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException(context.getString(io.github.oustery.opencodephone.R.string.settings_backup_open_failed))
                output.use { it.write(json.toString(2).toByteArray(Charsets.UTF_8)) }
                _state.update {
                    it.copy(dataMessage = context.getString(io.github.oustery.opencodephone.R.string.settings_backup_done))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(dataMessage = "Export failed: ${e.message?.take(200)}") }
            }
        }
    }

    /** Read a user-picked snapshot and ask for confirmation before applying. */
    fun onImportPicked(uri: android.net.Uri) {
        val context = graph.appContext
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: throw IllegalStateException(context.getString(io.github.oustery.opencodephone.R.string.settings_backup_open_failed))
                pendingImport = org.json.JSONObject(text)
                _state.update { it.copy(showImportConfirm = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(dataMessage = "Import failed: ${e.message?.take(200)}") }
            }
        }
    }

    fun dismissImportConfirm() {
        pendingImport = null
        _state.update { it.copy(showImportConfirm = false) }
    }

    fun confirmImport() {
        val json = pendingImport
        pendingImport = null
        _state.update { it.copy(showImportConfirm = false) }
        if (json == null) return
        val context = graph.appContext
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val applied = settings.importNonSecret(json)
                graph.refreshClient()
                graph.credentialsChanged()
                // Route the graph state so the imported choice/project take
                // effect immediately (bootstrap may need to run).
                val importedChoice = settings.setupChoice()
                if (importedChoice != graph.setupChoice.value) graph.chooseSetup(importedChoice)
                graph.selectProject(settings.selectedProject())
                reload()
                _state.update {
                    it.copy(dataMessage = context.getString(io.github.oustery.opencodephone.R.string.settings_import_done, applied))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(dataMessage = "Import failed: ${e.message?.take(200)}") }
            }
        }
    }

    /** Clear the crash-recovery draft copies (composer + editor buffer). */
    fun clearDrafts() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val store = io.github.oustery.opencodephone.core.settings.DraftStore(
                java.io.File(graph.appContext.filesDir, "drafts"),
            )
            store.clearChat()
            store.clearEditor()
            publishDataCleared(io.github.oustery.opencodephone.R.string.settings_data_item_drafts)
        }
    }

    /** Delete the app-managed attachment copies under workspace/uploads. */
    fun clearUploads() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val uploads = java.io.File(graph.layout.workspaceDir, "uploads")
            uploads.listFiles()?.forEach { it.deleteRecursively() }
            publishDataCleared(io.github.oustery.opencodephone.R.string.settings_data_item_uploads)
        }
    }

    /** Remove installer leftovers (downloaded archives, temp files). */
    fun clearInstallerCache() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            graph.layout.downloadDir.listFiles()?.forEach { it.deleteRecursively() }
            graph.layout.tmpDir.listFiles()?.forEach { it.deleteRecursively() }
            publishDataCleared(io.github.oustery.opencodephone.R.string.settings_data_item_cache)
        }
    }

    private fun publishDataCleared(itemRes: Int) {
        val context = graph.appContext
        _state.update {
            it.copy(dataMessage = context.getString(io.github.oustery.opencodephone.R.string.settings_data_done, context.getString(itemRes)))
        }
    }
}
