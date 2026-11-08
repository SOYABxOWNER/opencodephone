package io.github.oustery.opencodephone.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.core.linuxenv.ServerState
import io.github.oustery.opencodephone.core.model.MessageWithParts
import io.github.oustery.opencodephone.core.model.OcJson
import io.github.oustery.opencodephone.core.model.PermissionRequest
import io.github.oustery.opencodephone.core.model.Parts
import io.github.oustery.opencodephone.core.model.ProviderEntry
import io.github.oustery.opencodephone.core.model.ServerEvent
import io.github.oustery.opencodephone.core.model.Session
import io.github.oustery.opencodephone.core.model.UiPart
import io.github.oustery.opencodephone.core.settings.ModelSelection
import io.github.oustery.opencodephone.core.settings.ServerMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import io.github.oustery.opencodephone.core.opencode.throttledInvalidations
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ChatConnection { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class UiMessage(
    val id: String,
    val role: String,
    val parts: List<UiPart>,
)

data class ChatUiState(
    val connection: ChatConnection = ChatConnection.DISCONNECTED,
    val version: String = "",
    val sessions: List<Session> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<UiMessage> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val providers: List<ProviderEntry> = emptyList(),
    val connectedProviders: List<String> = emptyList(),
    val modelSelection: ModelSelection? = null,
    val permission: PermissionRequest? = null,
    val showSessions: Boolean = false,
    val showModels: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    private var eventsJob: Job? = null

    private val reloadSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    init {
        // React to server lifecycle.
        viewModelScope.launch {
            combine(graph.serverConfiguration, graph.runtime.serverState) { cfg, state -> cfg.mode to state }
                .collectLatest { (mode, serverState) ->
                    eventsJob?.cancel()
                    if (mode == ServerMode.REMOTE) {
                        connect()
                    } else when (serverState) {
                        is ServerState.Running -> connect()
                        is ServerState.Starting -> _state.update { it.copy(connection = ChatConnection.CONNECTING, error = null) }
                        is ServerState.Failed -> _state.update { it.copy(connection = ChatConnection.ERROR, error = serverState.message) }
                        ServerState.Stopped -> _state.update { it.copy(connection = ChatConnection.DISCONNECTED, busy = false) }
                    }
                }
        }

        // Throttled/coalesced reloads make progress even during continuous token streams.
        viewModelScope.launch {
            reloadSignal.throttledInvalidations().collect {
                _state.value.activeSessionId?.let { loadMessages(it) }
            }
        }
    }

    private suspend fun connect() {
        _state.update { it.copy(connection = ChatConnection.CONNECTING, error = null) }
        try {
            val health = graph.client.health()
            syncProviderKeys()
            loadProviders()
            loadSessions()
            subscribeEvents()
            _state.update {
                it.copy(connection = ChatConnection.CONNECTED, version = health.version)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(connection = ChatConnection.ERROR, error = e.message)
            }
        }
    }

    /** Push locally stored provider keys into the opencode server auth store. */
    private suspend fun syncProviderKeys() {
        graph.settings.providerKeys().forEach { key ->
            graph.client.setProviderAuth(
                key.providerId,
                buildJsonObject {
                    put("type", "api")
                    put("key", key.apiKey)
                },
            )
        }
    }

    private suspend fun loadProviders() {
        val providers = graph.client.listProviders()
        val selection = graph.settings.modelSelection()
        _state.update {
            it.copy(
                providers = providers.all,
                connectedProviders = providers.connected,
                modelSelection = selection.takeIf { s -> s.providerId.isNotBlank() },
            )
        }
    }

    private suspend fun loadSessions() {
        val sessions = graph.client.listSessions().sortedByDescending { it.time?.created ?: 0L }
        _state.update { state ->
            val active = state.activeSessionId?.takeIf { id -> sessions.any { it.id == id } }
                ?: sessions.firstOrNull()?.id
            state.copy(sessions = sessions, activeSessionId = active)
        }
        _state.value.activeSessionId?.let { loadMessages(it, required = true) }
    }

    private fun subscribeEvents() {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            try {
                graph.client.events().collect { event ->
                    handleEvent(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(connection = ChatConnection.ERROR, error = e.message, busy = false) }
                // Automatic reconnect/reconciliation is tracked separately as F-009.
            }
        }
    }

    private suspend fun handleEvent(event: ServerEvent) {
        when {
            event.type.startsWith("message.") -> reloadSignal.tryEmit(Unit)

            event.type == "session.status" -> {
                val props = event.properties as? JsonObject
                val sessionId = OcJson.run { props?.optString("sessionID") }
                val statusType = props?.get("status")?.let { status ->
                    OcJson.run { (status as? JsonObject)?.optString("type") }
                }
                _state.update { st ->
                    st.copy(
                        busy = if (sessionId == null || sessionId == st.activeSessionId) {
                            statusType == "busy" || statusType == "retry"
                        } else {
                            st.busy
                        },
                    )
                }
            }

            event.type.startsWith("session.") -> loadSessionsQuiet()

            event.type == "permission.updated" || event.type == "permission.asked" -> {
                val request = OcJson.permissionRequest(event.properties)
                if (request != null) {
                    _state.update { it.copy(permission = request) }
                }
            }

            event.type == "permission.replied" -> {
                _state.update { it.copy(permission = null) }
            }
        }
    }

    private suspend fun loadSessionsQuiet() {
        try {
            val sessions = graph.client.listSessions()
                .sortedByDescending { it.time?.created ?: 0L }
            _state.update { it.copy(sessions = sessions) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }

    private suspend fun loadMessages(sessionId: String, required: Boolean = false) {
        try {
            val raw: List<MessageWithParts> = graph.client.listMessages(sessionId)
            val ui = raw.map { msg ->
                UiMessage(
                    id = msg.info.id,
                    role = msg.info.role,
                    parts = Parts.uiParts(msg.parts),
                )
            }.filter { it.parts.isNotEmpty() }
            _state.update { it.copy(messages = ui) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
            if (required) throw e
        }
    }

    // ------------------------------------------------------------ Actions

    fun updateDraft(value: String) = _state.update { it.copy(draft = value) }

    fun newSession() {
        viewModelScope.launch {
            try {
                val session = graph.client.createSession(null)
                _state.update {
                    it.copy(activeSessionId = session.id, messages = emptyList(), showSessions = false)
                }
                loadSessionsQuiet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun selectSession(sessionId: String) {
        _state.update { it.copy(activeSessionId = sessionId, showSessions = false, messages = emptyList()) }
        viewModelScope.launch { loadMessages(sessionId) }
    }

    fun send() {
        val current = _state.value
        val sessionId = current.activeSessionId ?: return
        val text = current.draft.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(draft = "", busy = true) }
        viewModelScope.launch {
            try {
                val selection = current.modelSelection
                graph.client.promptAsync(
                    sessionId = sessionId,
                    text = text,
                    providerId = selection?.providerId,
                    modelId = selection?.modelId,
                    agent = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message, busy = false) }
            }
        }
    }

    fun abort() {
        val sessionId = _state.value.activeSessionId ?: return
        viewModelScope.launch {
            try {
                graph.client.abort(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun respondPermission(response: String) {
        val request = _state.value.permission ?: return
        viewModelScope.launch {
            try {
                graph.client.respondPermission(request.sessionId, request.permissionId, response)
                _state.update { it.copy(permission = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        val selection = ModelSelection(providerId, modelId)
        graph.settings.setModelSelection(selection)
        _state.update { it.copy(modelSelection = selection, showModels = false) }
    }

    fun showSessions(show: Boolean) = _state.update { it.copy(showSessions = show) }

    fun showModels(show: Boolean) = _state.update { it.copy(showModels = show) }

    fun dismissError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        eventsJob?.cancel()
        super.onCleared()
    }
}
