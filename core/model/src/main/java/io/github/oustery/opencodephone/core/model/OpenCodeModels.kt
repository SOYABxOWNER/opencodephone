package io.github.oustery.opencodephone.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DTOs for the OpenCode V1 server HTTP API (`opencode serve`).
 *
 * The server publishes an OpenAPI 3.1 spec at `/doc`; these models follow the
 * documented shapes but parse leniently (unknown keys are ignored) so minor
 * protocol drift does not break the client.
 */

@Serializable
data class Health(
    val healthy: Boolean = false,
    val version: String = "",
)

@Serializable
data class SessionTime(
    val created: Long? = null,
    val updated: Long? = null,
)

@Serializable
data class Session(
    val id: String,
    val title: String = "",
    @SerialName("parentID") val parentId: String? = null,
    val directory: String = "",
    val time: SessionTime? = null,
)

@Serializable
data class MessageInfo(
    val id: String,
    @SerialName("sessionID") val sessionId: String = "",
    val role: String = "user",
    val time: JsonElement? = null,
    @SerialName("modelID") val modelId: String? = null,
    @SerialName("providerID") val providerId: String? = null,
)

@Serializable
data class MessageWithParts(
    val info: MessageInfo,
    val parts: List<JsonElement> = emptyList(),
)

@Serializable
data class SessionStatus(
    @SerialName("sessionID") val sessionId: String = "",
    val type: String = "idle",
)

@Serializable
data class ModelCost(val input: Double? = null, val output: Double? = null)

@Serializable
data class ModelEntry(
    val id: String,
    val name: String = id,
    @SerialName("contextWindow") val contextWindow: Long? = null,
    val cost: ModelCost? = null,
)

@Serializable
data class ProviderEntry(
    val id: String,
    val name: String = id,
    val models: Map<String, ModelEntry> = emptyMap(),
    val env: List<String> = emptyList(),
)

@Serializable
data class ProviderListResponse(
    val default: Map<String, String> = emptyMap(),
    val all: List<ProviderEntry> = emptyList(),
    val connected: List<String> = emptyList(),
)

@Serializable
data class ConfigProvidersResponse(
    val providers: List<ProviderEntry> = emptyList(),
    val default: JsonElement? = null,
)

@Serializable
data class AgentInfo(
    val name: String,
)

@Serializable
data class FileNode(
    val type: String = "file",
    val name: String = "",
    val path: String = "",
)

@Serializable
data class FileContent(
    val type: String = "text",
    val content: String? = null,
)

@Serializable
data class TodoItem(
    val id: String = "",
    val content: String = "",
    val status: String = "",
    val priority: String = "",
)

@Serializable
data class PathInfo(
    val worktree: String = "",
    val root: String = "",
)

/** Server-sent event envelope: `{"type": "...", "properties": {...}}`. */
@Serializable
data class ServerEvent(
    val type: String,
    val properties: JsonElement? = null,
)

/** A permission request extracted from a `permission.updated` event. */
data class PermissionRequest(
    val sessionId: String,
    val permissionId: String,
    val type: String,
    val title: String,
)
