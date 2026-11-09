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
import io.github.oustery.opencodephone.core.settings.DraftStore
import io.github.oustery.opencodephone.core.settings.ModelSelection
import io.github.oustery.opencodephone.core.settings.ServerMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

/** Messages fetched per page (functional audit F-011). */
const val CHAT_MESSAGE_PAGE = 60

/** Explicit cap for the sessions list; the V1 endpoint defaults to the newest 100. */
const val CHAT_SESSIONS_LIMIT = 100

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
    // Shared project context (functional audit F-001): workspace-relative
    // path of the repository selected in Files, shown above the composer.
    val projectContext: String? = null,
    // Cursor pagination for long sessions (functional audit F-011):
    // non-null cursor means older messages are still available on the server.
    val earlierCursor: String? = null,
    val loadingEarlier: Boolean = false,
    // Session management (F-004) and the diff review sheet (F-005).
    val sessionQuery: String = "",
    val showDiff: Boolean = false,
    val diffLoading: Boolean = false,
    val diff: List<io.github.oustery.opencodephone.core.model.SessionFileDiff> = emptyList(),
)

class ChatViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    private var eventsJob: Job? = null
    private var messagesJob: Job? = null
    private var endpointEpoch = 0L
    private var activeClient = graph.client

    private val reloadSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    // Loaded history window (messages); grows by one page per "load earlier".
    private var historyWindow = CHAT_MESSAGE_PAGE

    // Crash-safe mirror of unsaved composer work (functional audit F-003).
    private val drafts = DraftStore(java.io.File(graph.appContext.filesDir, "drafts"))
    private var chatDraftJob: Job? = null

    init {
        // Restore unsaved work after process death; attachment copies live in
        // the workspace, so entries whose file is gone are silently dropped.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val saved = drafts.readChat() ?: return@launch
            val restored = saved.attachments.filter { java.io.File(it.absolutePath).isFile }
                .map { it.toPendingAttachment() }
            if (restored.size != saved.attachments.size || restored.isNotEmpty() || saved.text.isNotEmpty()) {
                _state.update {
                    if (it.draft.isEmpty() && it.attachments.isEmpty()) {
                        it.copy(draft = saved.text, attachments = restored)
                    } else {
                        it
                    }
                }
            }
        }
        // Attachment copies are kept after sending so the agent can revisit
        // them; week-old leftovers are swept instead of growing forever
        // (uploads orphan cleanup, functional audit F-003).
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val uploads = java.io.File(graph.layout.workspaceDir, "uploads")
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            uploads.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
        // Shared project context follows the Files selection (F-001).
        viewModelScope.launch {
            graph.selectedProject.collect { project ->
                _state.update { it.copy(projectContext = project) }
            }
        }

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
        val sessions = activeClient.listSessions(limit = CHAT_SESSIONS_LIMIT).sortedByDescending { it.time?.created ?: 0L }
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
            val sessions = activeClient.listSessions(limit = CHAT_SESSIONS_LIMIT)
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
            // The window keeps its size across throttled reloads so streamed
            // tokens do not collapse an expanded history back to one page.
            val page = client.listMessages(sessionId, limit = historyWindow)
            val ui = io.github.oustery.opencodephone.core.model.MessageTimeline.newestFirst(page.items).map { msg ->
                UiMessage(
                    id = msg.info.id,
                    role = msg.info.role,
                    parts = Parts.uiParts(msg.parts),
                )
            }.filter { it.parts.isNotEmpty() }
            _state.update {
                if (ticket == endpointEpoch && it.activeSessionId == sessionId) {
                    it.copy(messages = ui, earlierCursor = page.nextCursor)
                } else {
                    it
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
            if (required) throw e
        }
    }

    /** Fetch one older page of the active session and prepend it (F-011). */
    fun loadEarlier() {
        val sessionId = _state.value.activeSessionId ?: return
        val cursor = _state.value.earlierCursor ?: return
        if (_state.value.loadingEarlier) return
        val ticket = endpointEpoch
        val client = activeClient
        _state.update { it.copy(loadingEarlier = true) }
        viewModelScope.launch {
            try {
                val page = client.listMessages(sessionId, limit = CHAT_MESSAGE_PAGE, before = cursor)
                val older = io.github.oustery.opencodephone.core.model.MessageTimeline.newestFirst(page.items).map { msg ->
                    UiMessage(
                        id = msg.info.id,
                        role = msg.info.role,
                        parts = Parts.uiParts(msg.parts),
                    )
                }.filter { it.parts.isNotEmpty() }
                if (ticket == endpointEpoch) historyWindow += CHAT_MESSAGE_PAGE
                _state.update {
                    if (ticket == endpointEpoch && it.activeSessionId == sessionId) {
                        it.copy(
                            messages = older + it.messages,
                            earlierCursor = page.nextCursor,
                            loadingEarlier = false,
                        )
                    } else {
                        it.copy(loadingEarlier = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket != endpointEpoch) return@launch
                _state.update { it.copy(loadingEarlier = false, error = e.message) }
            }
        }
    }

    // ------------------------------------------------------------ Actions

    fun updateDraft(value: String) {
        _state.update { it.copy(draft = value) }
        scheduleChatDraft()
    }

    /** Mirror the composer into the draft store; cleared after a send. */
    private fun scheduleChatDraft() {
        chatDraftJob?.cancel()
        chatDraftJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            delay(400)
            persistChatDraft()
        }
    }

    private fun persistChatDraft(scope: CoroutineScope = viewModelScope) {
        val snapshot = _state.value
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (snapshot.draft.isBlank() && snapshot.attachments.isEmpty()) {
                drafts.clearChat()
            } else {
                drafts.writeChat(
                    DraftStore.ChatDraft(
                        text = snapshot.draft,
                        attachments = snapshot.attachments.map {
                            DraftStore.StoredAttachment(
                                it.id, it.displayName, it.mime, it.size, it.absolutePath, it.envPath,
                            )
                        },
                    ),
                )
            }
        }
    }

    private fun DraftStore.StoredAttachment.toPendingAttachment() = PendingAttachment(
        id = id, displayName = displayName, mime = mime, size = size,
        absolutePath = absolutePath, envPath = envPath,
    )

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
                // Remote servers cannot read phone files (F-002): refuse what
                // cannot be delivered instead of failing inside the agent run.
                val remote = graph.serverConfiguration.value.mode == ServerMode.REMOTE
                if (remote && (!io.github.oustery.opencodephone.core.opencode.Attachments.isInlineable(attachment.mime, attachment.displayName) ||
                        attachment.size > io.github.oustery.opencodephone.core.opencode.Attachments.INLINE_LIMIT_BYTES)) {
                    java.io.File(attachment.absolutePath).delete()
                    val limitKb = io.github.oustery.opencodephone.core.opencode.Attachments.INLINE_LIMIT_BYTES / 1024
                    _state.update {
                        it.copy(error = context.getString(io.github.oustery.opencodephone.R.string.chat_attach_remote_unsupported, limitKb))
                    }
                    return@launch
                }
                _state.update { it.copy(attachments = it.attachments + attachment) }
                persistChatDraft()
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
        persistChatDraft()
    }

    fun newSession() {
        val client = activeClient
        val ticket = endpointEpoch
        historyWindow = CHAT_MESSAGE_PAGE
        // Attribute the session to the selected project (F-001): the title
        // names the repository so the sessions list stays recognizable.
        val projectName = graph.selectedProject.value?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            try {
                val session = client.createSession(projectName)
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
        historyWindow = CHAT_MESSAGE_PAGE
        _state.update { it.copy(activeSessionId = sessionId, showSessions = false, messages = emptyList(), earlierCursor = null, loadingEarlier = false) }
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            loadMessages(sessionId)
            try { reconcileStatus() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
        }
    }

    /** Delete a session on the server and update the local list (F-004). */
    fun deleteSession(sessionId: String) {
        val client = activeClient
        val ticket = endpointEpoch
        val wasActive = _state.value.activeSessionId == sessionId
        viewModelScope.launch {
            try {
                client.deleteSession(sessionId)
                if (ticket != endpointEpoch) return@launch
                val remaining = _state.value.sessions.filterNot { it.id == sessionId }
                _state.update { it.copy(sessions = remaining) }
                when {
                    !wasActive -> Unit
                    remaining.isNotEmpty() -> selectSession(remaining.first().id)
                    else -> _state.update { it.copy(activeSessionId = null, messages = emptyList(), earlierCursor = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket != endpointEpoch) return@launch
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /** Rename a session through the V1 PATCH endpoint (F-004). */
    fun renameSession(sessionId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val client = activeClient
        val ticket = endpointEpoch
        viewModelScope.launch {
            try {
                client.renameSession(sessionId, trimmed)
                if (ticket != endpointEpoch) return@launch
                _state.update { st ->
                    st.copy(sessions = st.sessions.map { if (it.id == sessionId) it.copy(title = trimmed) else it })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket != endpointEpoch) return@launch
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun updateSessionQuery(value: String) = _state.update { it.copy(sessionQuery = value) }

    /** Open the diff review sheet for the active session (F-005). */
    fun showDiff(show: Boolean) {
        _state.update { it.copy(showDiff = show) }
        if (show) loadDiff()
    }

    private fun loadDiff() {
        val sessionId = _state.value.activeSessionId ?: return
        val client = activeClient
        val ticket = endpointEpoch
        _state.update { it.copy(diffLoading = true) }
        viewModelScope.launch {
            try {
                val diff = client.sessionDiff(sessionId)
                if (ticket == endpointEpoch) _state.update { it.copy(diff = diff, diffLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket != endpointEpoch) return@launch
                _state.update { it.copy(diffLoading = false, error = e.message) }
            }
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
        // Remote delivery (functional audit F-002): the server cannot resolve
        // app-side file:// paths, so text-like attachments ride along as
        // inline prompt text and binaries are refused before sending.
        val remote = graph.serverConfiguration.value.mode == ServerMode.REMOTE
        if (remote) {
            val undeliverable = sentAttachments.filter {
                !io.github.oustery.opencodephone.core.opencode.Attachments.isInlineable(it.mime, it.displayName) ||
                    it.size > io.github.oustery.opencodephone.core.opencode.Attachments.INLINE_LIMIT_BYTES
            }
            if (undeliverable.isNotEmpty()) {
                val limitKb = io.github.oustery.opencodephone.core.opencode.Attachments.INLINE_LIMIT_BYTES / 1024
                _state.update {
                    it.copy(error = graph.appContext.getString(io.github.oustery.opencodephone.R.string.chat_attach_remote_unsupported, limitKb))
                }
                return
            }
        }
        _state.update { it.copy(draft = "", attachments = emptyList(), busy = true) }
        persistChatDraft()
        val client = activeClient
        val ticket = endpointEpoch
        viewModelScope.launch {
            try {
                val selection = current.modelSelection
                val inlineText = if (remote && sentAttachments.isNotEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        sentAttachments.mapNotNull { file ->
                            runCatching {
                                io.github.oustery.opencodephone.core.opencode.Attachments.inlineBlock(
                                    file.displayName,
                                    java.io.File(file.absolutePath).readText(),
                                )
                            }.getOrNull()
                        }.joinToString("\n\n").ifBlank { null }
                    }
                } else {
                    null
                }
                client.promptAsync(
                    sessionId = sessionId,
                    text = sentDraft,
                    providerId = selection?.providerId,
                    modelId = selection?.modelId,
                    agent = null,
                    attachments = if (remote) emptyList() else sentAttachments.map { file ->
                        io.github.oustery.opencodephone.core.opencode.PromptAttachment(
                            path = file.envPath,
                            mime = file.mime,
                            filename = file.displayName,
                        )
                    },
                    inlineText = inlineText,
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
                persistChatDraft()
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
        chatDraftJob?.cancel()
        // viewModelScope is dead here; flush the final composer snapshot via
        // the app scope so nothing typed is lost to process death (F-003).
        persistChatDraft(graph.appScope)
        super.onCleared()
    }
}
