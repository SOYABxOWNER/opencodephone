package io.github.oustery.opencodephone.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared lenient JSON configuration and small extraction helpers used across
 * modules when parsing opencode server payloads whose shape may drift.
 */
object OcJson {

    val lenient: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun JsonObject.optString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    fun JsonObject.optObject(key: String): JsonObject? =
        this[key] as? JsonObject

    fun JsonObject.optStringList(key: String): List<String> =
        runCatching {
            val arr = this[key] as? JsonArray ?: return emptyList()
            arr.mapNotNull { el -> (el as? JsonPrimitive)?.contentOrNull }
        }.getOrDefault(emptyList())

    /** Extract a permission request from a `permission.updated` event payload. */
    fun permissionRequest(properties: JsonElement?, fallbackSessionId: String? = null): PermissionRequest? {
        val obj = properties as? JsonObject ?: return null
        val sessionId = obj.optString("sessionID") ?: fallbackSessionId ?: return null
        val id = obj.optString("id") ?: return null
        return PermissionRequest(
            sessionId = sessionId,
            permissionId = id,
            type = obj.optString("permission") ?: obj.optString("type").orEmpty(),
            title = obj.optString("title") ?: obj.optStringList("patterns").joinToString(", "),
        )
    }
}
