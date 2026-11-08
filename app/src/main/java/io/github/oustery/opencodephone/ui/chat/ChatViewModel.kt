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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import io.github.oustery.opencodephone.core.opencode.OpenCodeClient
import io.github.oustery.opencodephone.core.opencode.OpenCodeApiException
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
    private var messagesJob: Job? = null
    private var endpointEpoch = 0L
    private var activeClient = graph.client

    private val reloadSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    init {
        // React to server lifecycle.
        viewModelScope.launch {
            combine(graph.serverConfiguration, graph.runtime.serverState, graph.credentialsRevision) { cfg, state, _ -> cfg.mode to state }
                .collectLatest { (mode, serverState) ->
                    eventsJob?.cancel()
                    messagesJob?.cancel()
                    endpointEpoch++
                    if (activeClient !== graph.client) {
                        activeClient = graph.client
                        _state.update { ChatUiState(draft = it.draft) }
                    }
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
            val health = activeClient.health()
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
    private suspend fun syncProviderKeys() = graph.syncProviderCredentials(activeClient)

    private suspend fun loadProviders() {
        val providers = activeClient.listProviders()
        val selection = graph.settings.modelSelection()
        val selected = io.github.oustery.opencodephone.core.model.OpenCodeCatalog.choose(
            providers, selection.providerId, selection.modelId,
        )
        _state.update {
            it.copy(
                providers = io.github.oustery.opencodephone.core.model.OpenCodeCatalog.providers(providers),
                connectedProviders = providers.connected,
                modelSelection = selected?.let { ModelSelection("opencode", it.id) },
            )
        }
    }

    private suspend fun loadSessions() {
        val sessions = activeClient.listSessions().sortedByDescending { it.time?.created ?: 0L }
        _state.update { state ->
            val active = state.activeSessionId?.takeIf { id -> sessions.any { it.id == id } }
                ?: sessions.firstOrNull()?.id
            state.copy(sessions = sessions, activeSessionId = active)
        }
        _state.value.activeSessionId?.let { loadMessages(it, required = true) }
    }

    private suspend fun reconcileStatus(client: OpenCodeClient = activeClient) {
        val ticket = endpointEpoch
        val statuses = client.sessionStatuses()
        val permissions = client.pendingPermissions()
        if (ticket != endpointEpoch) return
        _state.update { state ->
            val status = (state.activeSessionId?.let { statuses[it] } as? JsonObject)?.get("type")?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
            state.copy(busy = status == "busy" || status == "retry",
                permission = permissions.firstOrNull { it.sessionId == state.activeSessionId })
        }
    }

    private fun subscribeEvents() {
        eventsJob?.cancel()
        val client = activeClient
        val ticket = endpointEpoch
        eventsJob = viewModelScope.launch {
            var attempts = 0
            while (isActive && ticket == endpointEpoch) {
                try {
                    if (attempts > 0) {
                        _state.update { it.copy(connection = ChatConnection.CONNECTING) }
                        client.health()
                        loadProviders()
                        loadSessions()
                        reconcileStatus(client)
                        _state.update { it.copy(connection = ChatConnection.CONNECTED, error = null) }
                    }
                    client.events().collect { event ->
                        if (ticket == endpointEpoch) {
                            if (event.type == "server.connected") {
                                reconcileStatus(client)
                                reloadSignal.tryEmit(Unit)
                                attempts = 0
                            } else handleEvent(event)
                        }
                    }
                    throw java.io.IOException("Event stream closed")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (ticket != endpointEpoch) return@launch
                    _state.update { it.copy(connection = ChatConnection.ERROR, error = e.message, busy = false) }
                    if (e is OpenCodeApiException && e.code in listOf(401, 403)) return@launch
                }
                attempts++
                delay(minOf(30_000L, 500L * (1L shl attempts.coerceAtMost(6))) + kotlin.random.Random.nextLong(250))
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
                if (request != null && request.sessionId == _state.value.activeSessionId) {
                    _state.update { it.copy(permission = request) }
                }
            }

            event.type == "permission.replied" -> {
                reconcileStatus()
            }
        }
    }

    private suspend fun loadSessionsQuiet() {
        try {
            val sessions = activeClient.listSessions()
                .sortedByDescending { it.time?.created ?: 0L }
            _state.update { it.copy(sessions = sessions) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }

    private suspend fun loadMessages(sessionId: String, required: Boolean = false) {
        val ticket = endpointEpoch
        val client = activeClient
        try {
            val raw: List<MessageWithParts> = client.listMessages(sessionId)
            val ui = raw.map { msg ->
                UiMessage(
                    id = msg.info.id,
                    role = msg.info.role,
                    parts = Parts.uiParts(msg.parts),
                )
            }.filter { it.parts.isNotEmpty() }
            _state.update { if (ticket == endpointEpoch && it.activeSessionId == sessionId) it.copy(messages = ui) else it }
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
        val client = activeClient
        val ticket = endpointEpoch
        viewModelScope.launch {
            try {
                val session = client.createSession(null)
                if (ticket != endpointEpoch) return@launch
                _state.update {
                    it.copy(activeSessionId = session.id, messages = emptyList(), showSessions = false)
                }
                loadSessionsQuiet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket != endpointEpoch) return@launch
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun selectSession(sessionId: String) {
        _state.update { it.copy(activeSessionId = sessionId, showSessions = false, messages = emptyList()) }
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            loadMessages(sessionId)
            try { reconcileStatus() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
        }
    }

    fun send() {
        val current = _state.value
        val sessionId = current.activeSessionId ?: return
        val text = current.draft.trim()
        if (text.isEmpty()) return
        if (current.modelSelection == null) {
            _state.update { it.copy(error = graph.appContext.getString(io.github.oustery.opencodephone.R.string.chat_models_empty)) }
            return
        }
        _state.update { it.copy(draft = "", busy = true) }
        val client = activeClient
        val ticket = endpointEpoch
        viewModelScope.launch {
            try {
                val selection = current.modelSelection
                client.promptAsync(
                    sessionId = sessionId,
                    text = text,
                    providerId = selection?.providerId,
                    modelId = selection?.modelId,
                    agent = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket != endpointEpoch) return@launch
                _state.update { it.copy(error = e.message, busy = false) }
            }
        }
    }

    fun abort() {
        val sessionId = _state.value.activeSessionId ?: return
        val client = activeClient
        val ticket = endpointEpoch
        viewModelScope.launch {
            try {
                client.abort(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket != endpointEpoch) return@launch
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun respondPermission(response: String) {
        val request = _state.value.permission ?: return
        val client = activeClient
        val ticket = endpointEpoch
        viewModelScope.launch {
            try {
                client.respondPermission(request.sessionId, request.permissionId, response)
                if (ticket == endpointEpoch) reconcileStatus(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket != endpointEpoch) return@launch
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        if (providerId != "opencode" || _state.value.providers.none { it.id == providerId && modelId in it.models }) return
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
