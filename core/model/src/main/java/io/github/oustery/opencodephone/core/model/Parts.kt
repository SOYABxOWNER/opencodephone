package io.github.oustery.opencodephone.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * UI-facing view of a message part.
 *
 * OpenCode messages are lists of typed parts (`text`, `reasoning`, `tool`,
 * `step-start`, `step-finish`, `file`, ...). Raw JSON is stored, but the UI
 * renders from this parsed hierarchy so unknown part types degrade safely.
 */
sealed interface UiPart {
    data class Text(val text: String) : UiPart
    data class Reasoning(val text: String) : UiPart
    data class Tool(
        val tool: String,
        val status: String,
        val title: String?,
        val output: String?,
        /** Failure explanation from the tool state (functional audit F-012). */
        val error: String? = null,
    ) : UiPart

    data class Step(val kind: String) : UiPart

    /** A `file` part (attachment) sent with a user message. */
    data class FileAttachment(val name: String, val mime: String) : UiPart

    data object Unknown : UiPart
}

object Parts {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Parse one raw part element into a [UiPart]. Never throws. */
    fun parse(element: JsonElement): UiPart {
        return try {
            val obj = element.jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "text" -> UiPart.Text(obj.optString("text").orEmpty())
                "reasoning" -> UiPart.Reasoning(obj.optString("text").orEmpty())
                "tool" -> {
                    val state = obj["state"] as? JsonObject
                    UiPart.Tool(
                        tool = obj.optString("tool") ?: "tool",
                        status = state?.optString("status") ?: "unknown",
                        title = state?.optString("title"),
                        output = state?.optString("output"),
                        error = state?.optString("error"),
                    )
                }
                "step-start" -> UiPart.Step("start")
                "step-finish" -> UiPart.Step("finish")
                "file" -> {
                    val url = obj.optString("url").orEmpty()
                    val name = obj.optString("filename")
                        ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "file" }
                    UiPart.FileAttachment(
                        name = name,
                        mime = obj.optString("mime").orEmpty(),
                    )
                }
                else -> UiPart.Unknown
            }
        } catch (_: Exception) {
            UiPart.Unknown
        }
    }

    private fun JsonObject.optString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Merge raw parts of a message into UI parts, dropping step markers. */
    fun uiParts(parts: List<JsonElement>): List<UiPart> =
        parts.map(::parse).filterNot { it is UiPart.Step }
}
