package io.github.oustery.opencodephone.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure JSON codec for the provider→API-key map. Separated from the Android
 * store so the logic is unit-testable on the JVM.
 */
object ProviderKeysCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(keys: Map<String, String>): String =
        buildJsonObject { keys.forEach { (id, key) -> put(id, key) } }.toString()

    fun decode(raw: String): Map<String, String> = try {
        val obj = json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
        obj.mapNotNull { (id, value) ->
            val key = (value as? JsonPrimitive)?.content ?: return@mapNotNull null
            id to key
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}
