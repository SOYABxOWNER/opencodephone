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
    val connectionTest: String? = null,
    val bootstrap: BootstrapState = BootstrapState(),
    val serverStateLabel: String = "",
    val serverError: String? = null,
    val serverLog: String = "",
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

    fun restartServer() = graph.restartLocalServer()

    fun reload() {
        _state.update {
            it.copy(
                server = settings.serverConfig(),
                providers = settings.providerKeys(),
                git = settings.gitConfig(),
                opencodePin = settings.opencodeVersionPin(),
            )
        }
    }

    // ------------------------------------------------------------- Server

    fun updateServer(transform: (ServerConfig) -> ServerConfig) {
        val updated = transform(_state.value.server)
        if (updated.port !in 1..65535) return
        settings.setServerConfig(updated)
        graph.refreshClient()
        _state.update { it.copy(server = updated) }
    }

    fun setMode(mode: ServerMode) = updateServer { it.copy(mode = mode) }

    fun setHost(value: String) = updateServer { it.copy(host = value.trim()) }

    fun setPort(value: String) {
        val port = value.toIntOrNull() ?: return
        updateServer { it.copy(port = port) }
    }

    fun setUsername(value: String) = updateServer { it.copy(username = value.trim()) }

    fun setPassword(value: String) = updateServer { it.copy(password = value) }

    fun testConnection() {
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
                    it.copy(connectionTest = "Failed: ${e.message?.take(120)}")
                }
            }
        }
    }

    // --------------------------------------------------------- Providers

    fun showAddProvider(show: Boolean) = _state.update { it.copy(showAddProvider = show) }

    fun saveProviderKey(providerId: String, apiKey: String) {
        if (providerId.isBlank() || apiKey.isBlank()) return
        settings.setProviderKey(providerId.trim().lowercase(), apiKey.trim())
        reload()
        _state.update { it.copy(showAddProvider = false) }
    }

    fun removeProviderKey(providerId: String) {
        settings.removeProviderKey(providerId)
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
        graph.bootstrap.reset()
        graph.bootstrap.start(settings.opencodeVersionPin())
    }

    fun workspacePath(): String = graph.layout.workspaceDir.absolutePath
}
