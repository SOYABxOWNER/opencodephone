package io.github.oustery.opencodephone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.core.linuxenv.ServerState
import io.github.oustery.opencodephone.core.model.BootstrapState
import io.github.oustery.opencodephone.core.opencode.OpenCodeClient
import io.github.oustery.opencodephone.core.settings.GitConfig
import io.github.oustery.opencodephone.core.settings.ProviderKey
import io.github.oustery.opencodephone.core.settings.ServerConfig
import io.github.oustery.opencodephone.core.settings.ServerMode
import io.github.oustery.opencodephone.core.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
)

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
            )
        }
    }

    // ------------------------------------------------------------- Server

    fun updateServer(transform: (ServerConfig) -> ServerConfig) {
        _state.update { it.copy(serverDirty = true, server = transform(it.server), providerSyncAllowed = false) }
    }
    fun setMode(mode: ServerMode) = updateServer { it.copy(mode = mode) }
    fun setHost(value: String) = updateServer { it.copy(host = value.trim()) }
    fun setPort(value: String) = _state.update { it.copy(serverDirty = true, serverPortText = value, providerSyncAllowed = false) }
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
                _state.update {
                    it.copy(connectionTest = "OK — opencode ${health.version}")
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

    // -------------------------------------------------------- Environment

    fun showReinstallConfirm(show: Boolean) = _state.update { it.copy(showReinstallConfirm = show) }

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
}
