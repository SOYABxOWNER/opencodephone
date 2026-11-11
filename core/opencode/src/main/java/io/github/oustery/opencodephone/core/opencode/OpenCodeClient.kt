package io.github.oustery.opencodephone.core.opencode

import io.github.oustery.opencodephone.core.model.AgentInfo
import io.github.oustery.opencodephone.core.model.ConfigProvidersResponse
import io.github.oustery.opencodephone.core.model.FileContent
import io.github.oustery.opencodephone.core.model.FileNode
import io.github.oustery.opencodephone.core.model.Health
import io.github.oustery.opencodephone.core.model.MessageWithParts
import io.github.oustery.opencodephone.core.model.OcJson
import io.github.oustery.opencodephone.core.model.PathInfo
import io.github.oustery.opencodephone.core.model.ProviderListResponse
import io.github.oustery.opencodephone.core.model.QuestionRequest
import io.github.oustery.opencodephone.core.model.ServerEvent
import io.github.oustery.opencodephone.core.model.Session
import io.github.oustery.opencodephone.core.model.SessionFileDiff
import io.github.oustery.opencodephone.core.model.TodoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import okhttp3.HttpUrl
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Target of the opencode server the client talks to. */
data class ConnectionConfig(
    val host: String = "127.0.0.1",
    val port: Int = 4096,
    val username: String? = null,
    val password: String? = null,
    val scheme: String = if (isLoopbackHost(host)) "http" else "https",
) {
    val baseUrl: String get() {
        require(scheme == "https" || (scheme == "http" && isLoopbackHost(host))) {
            "Remote connections require HTTPS. Use a loopback secure tunnel for an HTTP server."
        }
        return HttpUrl.Builder().scheme(scheme).host(host).port(port).build().toString().removeSuffix("/")
    }
}

/** Deliberately no DNS lookup: a remote hostname must not opt itself into cleartext. */
private fun isLoopbackHost(host: String): Boolean =
    host.lowercase() in setOf("127.0.0.1", "localhost", "::1", "[::1]")

class OpenCodeApiException(val code: Int, message: String) : Exception(message)

/** A local file attached to a prompt as an OpenCode V1 `file` part. */
data class PromptAttachment(
    /** Absolute path as seen by the opencode server (already inside proot). */
    val path: String,
    val mime: String,
    val filename: String,
)

/** Prompt request body for the V1 `/session/:id/prompt_async` endpoint. */
internal fun promptBody(
    text: String,
    providerId: String?,
    modelId: String?,
    agent: String?,
    attachments: List<PromptAttachment>,
    inlineText: String? = null,
): JsonObject = buildJsonObject {
    put("parts", buildJsonArray {
        if (text.isNotBlank()) {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        // Remote servers cannot resolve app-side `file://` paths, so text
        // attachments can be delivered as an extra text part instead
        // (functional audit F-002).
        if (!inlineText.isNullOrBlank()) {
            add(buildJsonObject {
                put("type", "text")
                put("text", inlineText)
            })
        }
        attachments.forEach { file ->
            add(buildJsonObject {
                put("type", "file")
                put("mime", file.mime.ifBlank { "application/octet-stream" })
                put("filename", file.filename)
                put("url", "file://${file.path}")
            })
        }
    })
    if (!providerId.isNullOrBlank() && !modelId.isNullOrBlank()) {
        putJsonObject("model") {
            put("providerID", providerId)
            put("modelID", modelId)
        }
    }
    if (!agent.isNullOrBlank()) put("agent", agent)
}

/**
 * Kotlin client for the OpenCode V1 headless server (`opencode serve`).
 *
 * Endpoint reference: https://opencode.ai/docs/server/
 * (recorded in docs/research/01-opencode-platform.md).
 */
class OpenCodeClient(
    private val client: OkHttpClient = defaultHttpClient(),
    @Volatile var config: ConnectionConfig = ConnectionConfig(),
) {

    private val json = OcJson.lenient
    // Never follow an HTTPS -> HTTP redirect with credentials or a prompt body.
    private val secureClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val loopbackClient = secureClient.newBuilder().proxy(java.net.Proxy.NO_PROXY).build()
    private fun transport(snapshot: ConnectionConfig): OkHttpClient =
        if (isLoopbackHost(snapshot.host)) loopbackClient else secureClient

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Encode a query parameter value. */
        fun q(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun Request.Builder.withAuth(snapshot: ConnectionConfig): Request.Builder {
        val pass = snapshot.password
        if (!pass.isNullOrBlank()) {
            header("Authorization", Credentials.basic(snapshot.username?.ifBlank { "opencode" } ?: "opencode", pass))
        }
        return this
    }

    /** Execute a request, returning the body text or null for an empty body. */
    private suspend fun execute(
        method: String,
        path: String,
        body: JsonElement? = null,
    ): String? = executeDetailed(method, path, body).first

    /**
     * Execute a request returning the body text and the value of the
     * `X-Next-Cursor` response header used by the V1 message pagination
     * (functional audit F-011).
     */
    private suspend fun executeDetailed(
        method: String,
        path: String,
        body: JsonElement? = null,
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val snapshot = config
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyStr = body?.let { json.encodeToString(JsonElement.serializer(), it) }
        val request = Request.Builder()
            .url(snapshot.baseUrl + path)
            .withAuth(snapshot)
            .header("Accept", "application/json")
            .method(method, bodyStr?.toRequestBody(mediaType))
            .build()
        transport(snapshot).newCall(request).consumeCancellable { response: Response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OpenCodeApiException(
                    response.code,
                    "HTTP ${response.code} ${method} $path: ${text.take(300)}",
                )
            }
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (text.isNotBlank() && (contentType.contains("text/html") || text.trimStart().startsWith("<"))) {
                throw OpenCodeApiException(
                    response.code,
                    "Expected OpenCode V1 JSON at $path, but received HTML. " +
                        "OpenCode V2 has a different API; use a 1.x server or check the host and port.",
                )
            }
            text.ifBlank { null } to response.header("X-Next-Cursor")
        }
    }

    private suspend fun <T> executeParsed(
        method: String,
        path: String,
        body: JsonElement? = null,
        serializer: KSerializer<T>,
    ): T {
        val text = execute(method, path, body)
            ?: throw OpenCodeApiException(-1, "Empty response for $path")
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw OpenCodeApiException(-1, "Invalid OpenCode V1 JSON response for $path. Check the server version and address.")
        }
    }

    // ---------------------------------------------------------------- Global

    suspend fun health(): Health {
        val health = executeParsed("GET", "/global/health", serializer = Health.serializer())
        if (!health.healthy || !health.version.startsWith("1.")) {
            throw OpenCodeApiException(-1, "Server is not a healthy OpenCode V1 API (expected healthy=true and version 1.x).")
        }
        return health
    }

    /** SSE stream of all bus events. First event is `server.connected`. */
    fun events(): Flow<ServerEvent> = eventStream("/event")

    /** SSE stream of global events. */
    fun globalEvents(): Flow<ServerEvent> = eventStream("/global/event")

    private fun eventStream(path: String): Flow<ServerEvent> = callbackFlow {
        val snapshot = config
        val request = Request.Builder()
            .url(snapshot.baseUrl + path)
            .withAuth(snapshot)
            .build()
        val source = EventSources.createFactory(transport(snapshot)).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    try {
                        val result = trySend(json.decodeFromString(ServerEvent.serializer(), data))
                        if (result.isFailure && !isClosedForSend) close(IllegalStateException("SSE buffer overflow"))
                    } catch (e: Exception) {
                        // Unparsable events must not pass unseen (exception
                        // audit): the stream stays alive, but a synthetic
                        // parse-error event reaches the UI as a notice.
                        trySend(
                            ServerEvent(
                                "client.event_parse_error",
                                buildJsonObject {
                                    put("error", (e.message ?: e.javaClass.simpleName).take(200))
                                },
                            ),
                        )
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    close(
                        OpenCodeApiException(
                            response?.code ?: -1,
                            "SSE stream failure: ${t?.message ?: response?.message ?: "unknown"}",
                        ),
                    )
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }
            },
        )
        awaitClose { source.cancel() }
    }

    suspend fun sessionStatuses(): JsonObject =
        executeParsed("GET", "/session/status", serializer = JsonObject.serializer())

    suspend fun pendingPermissions(): List<io.github.oustery.opencodephone.core.model.PermissionRequest> =
        executeParsed("GET", "/permission", serializer = ListSerializer(JsonElement.serializer()))
            .mapNotNull { OcJson.permissionRequest(it) }

    // -------------------------------------------------------------- Sessions

    /**
     * List sessions. The V1 endpoint has no cursor envelope; [limit] caps the
     * page server-side (the pinned server defaults to the newest 100).
     */
    suspend fun listSessions(limit: Int? = null): List<Session> {
        val query = limit?.let { "?limit=$it" }.orEmpty()
        return executeParsed("GET", "/session$query", serializer = ListSerializer(Session.serializer()))
    }

    suspend fun createSession(title: String?): Session {
        val body = buildJsonObject {
            if (!title.isNullOrBlank()) put("title", title)
        }
        return executeParsed("POST", "/session", body, Session.serializer())
    }

    suspend fun deleteSession(sessionId: String) {
        execute("DELETE", "/session/${q(sessionId)}")
    }

    /** Rename a session through the V1 update endpoint (`PATCH /session/:id`). */
    suspend fun renameSession(sessionId: String, title: String): Session {
        val body = buildJsonObject { put("title", title) }
        return executeParsed("PATCH", "/session/${q(sessionId)}", body, Session.serializer())
    }

    /**
     * The agent's task plan for a session (`GET /session/:id/todo`, pinned
     * v1.19.5 returns `[{content, status, priority}]`, functional audit F-010).
     */
    suspend fun sessionTodos(sessionId: String): List<TodoItem> {
        val text = execute("GET", "/session/${q(sessionId)}/todo") ?: return emptyList()
        val arr = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching { json.decodeFromString(TodoItem.serializer(), el.toString()) }.getOrNull()
        }
    }

    /**
     * Revert the session to before a message (`POST /session/:id/revert` with
     * `{messageID}`, functional audit F-014). The server stores a revert
     * marker; messages from that point onward leave the context until
     * [unrevert] is called.
     */
    suspend fun revert(sessionId: String, messageId: String) {
        execute("POST", "/session/${q(sessionId)}/revert", buildJsonObject {
            put("messageID", messageId)
        })
    }

    /** Restore all reverted messages (`POST /session/:id/unrevert`, F-014). */
    suspend fun unrevert(sessionId: String) {
        execute("POST", "/session/${q(sessionId)}/unrevert", buildJsonObject { })
    }

    /** One page of session messages (V1 `limit` + `before` cursor, F-011). */
    data class MessagePage(
        val items: List<MessageWithParts>,
        /** Opaque cursor for the next, older page; null when history is exhausted. */
        val nextCursor: String?,
        /** Returned items that could not be parsed; they are hidden from the
         *  timeline, so the UI must surface this count (exception audit). */
        val dropped: Int = 0,
    )

    /**
     * List messages of a session. With [limit] set the server returns the
     * newest [limit] messages (oldest first inside the page) plus an
     * `X-Next-Cursor` header while older messages remain; pass that cursor
     * back as [before] to fetch the next page. Without [limit] the whole
     * session is returned, matching the pre-pagination behaviour.
     */
    suspend fun listMessages(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
    ): MessagePage {
        require(before == null || limit != null) { "V1 pagination requires limit together with before" }
        val params = buildList {
            if (limit != null) add("limit=$limit")
            if (before != null) add("before=${q(before)}")
        }.joinToString("&")
        val path = "/session/${q(sessionId)}/message" + if (params.isEmpty()) "" else "?$params"
        val (bodyText, nextCursor) = executeDetailed("GET", path)
        val text = bodyText ?: throw OpenCodeApiException(-1, "Empty response for $path")
        val arr = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray
            ?: throw OpenCodeApiException(-1, "Invalid OpenCode V1 JSON response for $path. Check the server version and address.")
        var dropped = 0
        val items = arr.mapNotNull { el ->
            runCatching { json.decodeFromString(MessageWithParts.serializer(), el.toString()) }
                .getOrElse { dropped++; null }
        }
        return MessagePage(items, nextCursor, dropped)
    }

    /**
     * Send a user prompt asynchronously; completion arrives through the
     * `/event` SSE stream (returns HTTP 204 with no body).
     *
     * [attachments] become OpenCode V1 `file` parts. [PromptAttachment.path]
     * must be a path visible to the opencode server (inside proot the
     * workspace is mounted at `/root/projects`, not at the app-side path).
     */
    suspend fun promptAsync(
        sessionId: String,
        text: String,
        providerId: String? = null,
        modelId: String? = null,
        agent: String? = null,
        attachments: List<PromptAttachment> = emptyList(),
        inlineText: String? = null,
    ) {
        execute(
            "POST",
            "/session/${q(sessionId)}/prompt_async",
            promptBody(text, providerId, modelId, agent, attachments, inlineText),
        )
    }

    suspend fun abort(sessionId: String) {
        execute("POST", "/session/${q(sessionId)}/abort", buildJsonObject { })
    }

    /**
     * Respond to a permission request (`POST /session/:id/permissions/:id`).
     * Contract verified against the pinned `sst/opencode` v1.19.5 source
     * (`PermissionResponsePayload` in
     * `packages/opencode/src/server/routes/instance/httpapi/groups/session.ts`):
     * the body is `{response: "once" | "always" | "reject"}`. Do NOT confuse
     * this session-scoped (deprecated-but-active) route with the newer
     * experimental `POST /permission/:requestID/reply`, which takes
     * `{reply: …, message?}` — sending `{reply}` here fails validation with
     * `BadRequest: Missing key at ["response"]`.
     */
    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: String,
    ) {
        val body = buildJsonObject { put("response", response) }
        execute("POST", "/session/${q(sessionId)}/permissions/${q(permissionId)}", body)
    }

    // ------------------------------------------------------------- Questions

    /**
     * Pending agent question requests (v1.19.5 `GET /question`, pinned
     * `QuestionV1.Request[]`): the agent asks through the `question` tool and
     * blocks until the user replies or rejects the request.
     */
    suspend fun pendingQuestions(): List<QuestionRequest> {
        val text = execute("GET", "/question") ?: return emptyList()
        val arr = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching { json.decodeFromString(QuestionRequest.serializer(), el.toString()) }.getOrNull()
        }
    }

    /**
     * Answer a question request (`POST /question/:id/reply`, pinned
     * `QuestionV1.Reply`). Each inner list holds the selected labels for the
     * question at the same index; an empty list leaves that question
     * unanswered (the server reports it back to the agent as such).
     */
    suspend fun replyQuestion(requestId: String, answers: List<List<String>>) {
        val body = buildJsonObject {
            put("answers", buildJsonArray {
                answers.forEach { selected ->
                    add(buildJsonArray { selected.forEach { label -> add(label) } })
                }
            })
        }
        execute("POST", "/question/${q(requestId)}/reply", body)
    }

    /** Dismiss a question request without answering (`POST /question/:id/reject`). */
    suspend fun rejectQuestion(requestId: String) {
        execute("POST", "/question/${q(requestId)}/reject", buildJsonObject { })
    }

    // -------------------------------------------------------- Config & model

    suspend fun listProviders(): ProviderListResponse =
        executeParsed("GET", "/provider", serializer = ProviderListResponse.serializer())

    suspend fun configProviders(): ConfigProvidersResponse =
        executeParsed("GET", "/config/providers", serializer = ConfigProvidersResponse.serializer())

    suspend fun listAgents(): List<AgentInfo> =
        executeParsed("GET", "/agent", serializer = ListSerializer(AgentInfo.serializer()))

    /**
     * Auth methods the server exposes per provider (`GET /provider/auth`,
     * pinned v1.19.5 `ProviderAuth.Methods` = `Record<providerID, Method[]>`).
     * An empty map means the server has no interactive auth surface.
     */
    suspend fun providerAuthMethods(): Map<String, List<io.github.oustery.opencodephone.core.model.ProviderAuthMethod>> {
        val text = execute("GET", "/provider/auth") ?: return emptyMap()
        val obj = json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
        return obj.entries.mapNotNull { (providerId, methodsEl) ->
            val arr = methodsEl as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            val methods = arr.mapNotNull { el ->
                runCatching {
                    json.decodeFromString(io.github.oustery.opencodephone.core.model.ProviderAuthMethod.serializer(), el.toString())
                }.getOrNull()
            }
            if (methods.isEmpty()) null else providerId to methods
        }.toMap()
    }

    /**
     * Start an OAuth authorization for a provider method
     * (`POST /provider/:id/oauth/authorize`, pinned
     * `ProviderAuth.Authorization`). A null result means the flow finished
     * without a redirect (credentials were stored server-side directly).
     */
    suspend fun providerOAuthAuthorize(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): io.github.oustery.opencodephone.core.model.ProviderAuthorization? {
        val body = buildJsonObject {
            put("method", methodIndex)
            if (inputs.isNotEmpty()) {
                putJsonObject("inputs") { inputs.forEach { (key, value) -> put(key, value) } }
            }
        }
        val text = execute("POST", "/provider/${q(providerId)}/oauth/authorize", body) ?: return null
        return runCatching {
            json.decodeFromString(io.github.oustery.opencodephone.core.model.ProviderAuthorization.serializer(), text)
        }.getOrNull()
    }

    /** Complete an OAuth flow (`POST /provider/:id/oauth/callback`). */
    suspend fun providerOAuthCallback(providerId: String, methodIndex: Int, code: String? = null) {
        val body = buildJsonObject {
            put("method", methodIndex)
            if (!code.isNullOrBlank()) put("code", code)
        }
        execute("POST", "/provider/${q(providerId)}/oauth/callback", body)
    }

    /** Store provider credentials, e.g. `{"type":"api","key":"..."}`. */
    suspend fun setProviderAuth(providerId: String, credentials: JsonObject) {
        execute("PUT", "/auth/${q(providerId)}", credentials)
    }

    suspend fun removeProviderAuth(providerId: String) {
        execute("DELETE", "/auth/${q(providerId)}")
    }

    suspend fun apiCredentials(providerId: String, apiKey: String): JsonObject = buildJsonObject {
        put("type", "api")
        put("key", apiKey)
    }

    // ----------------------------------------------------------------- Files

    suspend fun listFiles(path: String = ""): List<FileNode> {
        val text = execute("GET", "/file?path=${q(path)}") ?: return emptyList()
        val arr = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching { json.decodeFromString(FileNode.serializer(), el.toString()) }.getOrNull()
        }
    }

    suspend fun readFile(path: String): FileContent? {
        val text = execute("GET", "/file/content?path=${q(path)}") ?: return null
        return runCatching { json.decodeFromString(FileContent.serializer(), text) }.getOrNull()
    }

    /**
     * Files changed by the agent in this session (`SnapshotFileDiff` on the
     * pinned V1 server: `{file, patch, additions, deletions, status}`).
     */
    suspend fun sessionDiff(sessionId: String): List<SessionFileDiff> {
        val text = execute("GET", "/session/${q(sessionId)}/diff") ?: return emptyList()
        val arr = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            fun str(key: String): String? = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
            fun num(key: String): Long = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            SessionFileDiff(
                file = str("file").orEmpty(),
                patch = str("patch"),
                additions = num("additions"),
                deletions = num("deletions"),
                status = str("status"),
            )
        }
    }

    suspend fun currentPath(): PathInfo =
        executeParsed("GET", "/path", serializer = PathInfo.serializer())
}
