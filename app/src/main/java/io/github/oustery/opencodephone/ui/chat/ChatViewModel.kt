package io.github.oustery.opencodephone.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.core.linuxenv.ServerState
import io.github.oustery.opencodephone.core.linuxenv.PRootCommand
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

/** Maximum attachment size copied into the workspace (OpenCode's own limit). */
const val MAX_ATTACHMENT_BYTES: Int = 20 * 1024 * 1024

/** A file picked in the chat composer, already copied into the workspace. */
data class PendingAttachment(
    val id: String,
    val displayName: String,
    val mime: String,
    val size: Long,
    /** Absolute path on the app filesystem (inside the workspace). */
    val absolutePath: String,
    /** Same file as the opencode server sees it (workspace mount point). */
    val envPath: String,
)

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
    val attachments: List<PendingAttachment> = emptyList(),
    val error: String? = null,
    // Skeleton gates (M3E audit E-017): sheets show placeholders instead of
    // a misleading "empty" label while the first fetch is still in flight.
    val sessionsLoading: Boolean = false,
    val providersLoading: Boolean = false,
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
                        _state.update { ChatUiState(draft = it.draft, attachments = it.attachments) }
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
        _state.update {
            it.copy(
                connection = ChatConnection.CONNECTING,
                error = null,
                sessionsLoading = true,
                providersLoading = true,
            )
        }
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
                it.copy(
                    connection = ChatConnection.ERROR,
                    error = e.message,
                    sessionsLoading = false,
                    providersLoading = false,
                )
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
                providersLoading = false,
            )
        }
    }

    private suspend fun loadSessions() {
        val sessions = activeClient.listSessions().sortedByDescending { it.time?.created ?: 0L }
        _state.update { state ->
            val active = state.activeSessionId?.takeIf { id -> sessions.any { it.id == id } }
                ?: sessions.firstOrNull()?.id
            state.copy(sessions = sessions, activeSessionId = active, sessionsLoading = false)
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
            val ui = io.github.oustery.opencodephone.core.model.MessageTimeline.newestFirst(raw).map { msg ->
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

    /**
     * Copy a picked content [Uri] into the workspace (`uploads/`), from where
     * the agent can read it and OpenCode resolves the `file` part. Shows an
     * error in the composer on failure.
     */
    fun attachUri(uri: Uri?) {
        if (uri == null) return
        val context = graph.appContext
        viewModelScope.launch {
            try {
                val attachment = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    copyToWorkspace(context, uri)
                }
                _state.update { it.copy(attachments = it.attachments + attachment) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = context.getString(
                            io.github.oustery.opencodephone.R.string.chat_attach_failed,
                            e.message ?: "unknown",
                        ),
                    )
                }
            }
        }
    }

    private fun copyToWorkspace(
        context: android.content.Context,
        uri: Uri,
    ): PendingAttachment {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, uri)
        val mime = resolver.getType(uri).orEmpty()
        val uploads = java.io.File(graph.layout.workspaceDir, "uploads")
        uploads.mkdirs()
        val target = uniqueTarget(uploads, displayName)
        resolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_ATTACHMENT_BYTES) {
                        output.close()
                        target.delete()
                        val limitMb = MAX_ATTACHMENT_BYTES / (1024 * 1024)
                        throw IllegalStateException(
                            context.getString(io.github.oustery.opencodephone.R.string.chat_attach_too_large, limitMb),
                        )
                    }
                    output.write(buffer, 0, count)
                }
            }
        } ?: run {
            throw IllegalStateException(context.getString(io.github.oustery.opencodephone.R.string.chat_attach_unreadable))
        }
        val relative = target.relativeTo(graph.layout.workspaceDir).path
        return PendingAttachment(
            id = java.util.UUID.randomUUID().toString(),
            displayName = displayName,
            mime = mime,
            size = target.length(),
            absolutePath = target.absolutePath,
            envPath = PRootCommand.WORKSPACE_MOUNT + "/" + relative,
        )
    }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(nameIdx)
                if (!name.isNullOrBlank()) return name.take(120)
            }
        }
        return uri.lastPathSegment?.take(120) ?: "file"
    }

    /** Keep the picked name; only disambiguate on collision or unsafe characters. */
    private fun uniqueTarget(dir: java.io.File, displayName: String): java.io.File {
        val base = displayName.substringAfterLast('/').ifBlank { "file" }
        val safe = base.replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]"), "_").ifBlank { "file" }
        val candidate = java.io.File(dir, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var index = 1
        while (true) {
            val numbered = java.io.File(dir, "$stem-$index$ext")
            if (!numbered.exists()) return numbered
            index++
        }
    }

    fun removeAttachment(id: String) {
        val removed = _state.value.attachments.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
        // The copy exists only for this unsent draft; a sent one stays for the agent.
        java.io.File(removed.absolutePath).delete()
    }

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
        if (current.busy) return
        val sessionId = current.activeSessionId ?: return
        val text = current.draft.trim()
        if (text.isEmpty() && current.attachments.isEmpty()) return
        if (current.modelSelection == null) {
            _state.update { it.copy(error = graph.appContext.getString(io.github.oustery.opencodephone.R.string.chat_models_empty)) }
            return
        }
        val sentDraft = text
        val sentAttachments = current.attachments
        _state.update { it.copy(draft = "", attachments = emptyList(), busy = true) }
        val client = activeClient
        val ticket = endpointEpoch
        viewModelScope.launch {
            try {
                val selection = current.modelSelection
                client.promptAsync(
                    sessionId = sessionId,
                    text = sentDraft,
                    providerId = selection?.providerId,
                    modelId = selection?.modelId,
                    agent = null,
                    attachments = sentAttachments.map { file ->
                        io.github.oustery.opencodephone.core.opencode.PromptAttachment(
                            path = file.envPath,
                            mime = file.mime,
                            filename = file.displayName,
                        )
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket != endpointEpoch) return@launch
                // Restore the composer so nothing the user typed is lost.
                _state.update {
                    it.copy(
                        error = e.message,
                        busy = false,
                        draft = if (it.draft.isBlank()) sentDraft else it.draft,
                        attachments = it.attachments + sentAttachments,
                    )
                }
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
