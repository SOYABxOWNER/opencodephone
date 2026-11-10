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
    /** Non-null while the session is reverted at a message (F-014). */
    val revert: SessionRevert? = null,
)

/**
 * The revert marker stored on a reverted session (`POST /session/:id/revert`):
 * messages from [messageId] onward leave the conversation context until
 * `unrevert` restores them.
 */
@Serializable
data class SessionRevert(
    @SerialName("messageID") val messageId: String = "",
    @SerialName("partID") val partId: String? = null,
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
    /** `primary`, `subagent` or `all` (pinned v1.19.5 agent schema). */
    val mode: String? = null,
    val hidden: Boolean? = null,
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

/**
 * One file changed by the agent in a session (`GET /session/:id/diff`,
 * `SnapshotFileDiff`). [patch] carries the unified diff text; the parser
 * keeps null when the server omits it.
 */
data class SessionFileDiff(
    val file: String = "",
    val patch: String? = null,
    val additions: Long = 0,
    val deletions: Long = 0,
    val status: String? = null,
)

/** Server-sent event envelope: `{"type": "...", "properties": {...}}`. */
@Serializable
data class ServerEvent(
    val type: String,
    val properties: JsonElement? = null,
)

/** A permission request extracted from `permission.asked` (pinned v1.19.5 `PermissionV1.Request`). */
data class PermissionRequest(
    val sessionId: String,
    val permissionId: String,
    val type: String,
    val title: String,
    /** Patterns the request covers (e.g. the command fragments that matched). */
    val patterns: List<String> = emptyList(),
    /** Shell command carried in `metadata.command`, when the tool provides it. */
    val command: String? = null,
)

/** One selectable answer of an agent question (pinned v1.19.5 `QuestionV1.Option`). */
@Serializable
data class QuestionOption(
    val label: String = "",
    val description: String = "",
)

/**
 * A single question the agent asks through the `question` tool
 * (pinned v1.19.5 `QuestionV1.Info`). [multiple] selects several options;
 * [custom] allows typing an answer instead of (or besides) the options —
 * upstream defaults it to true, so null keeps the custom input available.
 */
@Serializable
data class QuestionInfo(
    val question: String = "",
    val header: String = "",
    val options: List<QuestionOption> = emptyList(),
    val multiple: Boolean? = null,
    val custom: Boolean? = null,
)

/**
 * A pending question request from the `question.asked` event or
 * `GET /question` (pinned v1.19.5 `QuestionV1.Request`). The agent blocks on
 * the answer until the client replies or rejects the request.
 */
@Serializable
data class QuestionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val questions: List<QuestionInfo> = emptyList(),
)

/** One input step of a provider auth method (pinned v1.19.5 `ProviderAuth.Prompt`). */
@Serializable
data class ProviderAuthPrompt(
    /** `text` or `select`. */
    val type: String = "text",
    val key: String = "",
    val message: String = "",
    val placeholder: String? = null,
    val options: List<ProviderAuthPromptOption> = emptyList(),
)

@Serializable
data class ProviderAuthPromptOption(
    val label: String = "",
    val value: String = "",
    val hint: String? = null,
)

/**
 * An auth method a provider exposes (`GET /provider/auth`, pinned v1.19.5
 * `ProviderAuth.Method`): `type: "api"` stores a plain API key, `type:
 * "oauth"` drives the interactive browser flow. The methods come from the
 * upstream auth plugins (github-copilot, ChatGPT, xai, azure, …).
 */
@Serializable
data class ProviderAuthMethod(
    val type: String = "api",
    val label: String = "",
    val prompts: List<ProviderAuthPrompt> = emptyList(),
)

/**
 * Redirect of an OAuth provider flow (`POST /provider/:id/oauth/authorize`,
 * pinned v1.19.5 `ProviderAuth.Authorization`). [method] `auto` completes in
 * the browser on its own; `code` needs the pasted authorization code.
 */
@Serializable
data class ProviderAuthorization(
    val url: String = "",
    val method: String = "code",
    val instructions: String = "",
)
