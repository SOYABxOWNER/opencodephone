package io.github.oustery.opencodephone.core.model

import kotlinx.serialization.json.*

/** Reverse-layout contract: newest first. V1 time.created is epoch milliseconds.
 * Equal/missing timestamps preserve the API's chronological order, reversed for display.
 */
object MessageTimeline {
    fun newestFirst(messages: List<MessageWithParts>): List<MessageWithParts> = messages.withIndex()
        .sortedWith(compareByDescending<IndexedValue<MessageWithParts>> {
            ((it.value.info.time as? JsonObject)?.get("created") as? JsonPrimitive)?.longOrNull ?: 0L
        }.thenByDescending { it.index }).map { it.value }
}
