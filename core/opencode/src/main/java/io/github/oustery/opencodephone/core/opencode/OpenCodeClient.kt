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
import io.github.oustery.opencodephone.core.model.ServerEvent
import io.github.oustery.opencodephone.core.model.Session
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
): JsonObject = buildJsonObject {
    put("parts", buildJsonArray {
        if (text.isNotBlank()) {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
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
    ): String? = withContext(Dispatchers.IO) {
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
            text.ifBlank { null }
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
                    } catch (_: Exception) {
                        // Ignore unparsable events; the stream continues.
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

    suspend fun listSessions(): List<Session> =
        executeParsed("GET", "/session", serializer = ListSerializer(Session.serializer()))

    suspend fun createSession(title: String?): Session {
        val body = buildJsonObject {
            if (!title.isNullOrBlank()) put("title", title)
        }
        return executeParsed("POST", "/session", body, Session.serializer())
    }

    suspend fun deleteSession(sessionId: String) {
        execute("DELETE", "/session/${q(sessionId)}")
    }

    suspend fun listMessages(sessionId: String): List<MessageWithParts> =
        executeParsed(
            "GET",
            "/session/${q(sessionId)}/message",
            serializer = ListSerializer(MessageWithParts.serializer()),
        )

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
    ) {
        execute("POST", "/session/${q(sessionId)}/prompt_async", promptBody(text, providerId, modelId, agent, attachments))
    }

    suspend fun abort(sessionId: String) {
        execute("POST", "/session/${q(sessionId)}/abort", buildJsonObject { })
    }

    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: String,
        remember: Boolean? = null,
    ) {
        val body = buildJsonObject {
            put("response", response)
            if (remember != null) put("remember", remember)
        }
        execute("POST", "/session/${q(sessionId)}/permissions/${q(permissionId)}", body)
    }

    // -------------------------------------------------------- Config & model

    suspend fun listProviders(): ProviderListResponse =
        executeParsed("GET", "/provider", serializer = ProviderListResponse.serializer())

    suspend fun configProviders(): ConfigProvidersResponse =
        executeParsed("GET", "/config/providers", serializer = ConfigProvidersResponse.serializer())

    suspend fun listAgents(): List<AgentInfo> =
        executeParsed("GET", "/agent", serializer = ListSerializer(AgentInfo.serializer()))

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

    suspend fun sessionDiff(sessionId: String): List<JsonElement> {
        val text = execute("GET", "/session/${q(sessionId)}/diff") ?: return emptyList()
        val arr = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.toList()
    }

    suspend fun currentPath(): PathInfo =
        executeParsed("GET", "/path", serializer = PathInfo.serializer())
}
