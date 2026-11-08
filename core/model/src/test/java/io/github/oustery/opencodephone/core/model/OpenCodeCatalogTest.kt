package io.github.oustery.opencodephone.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OpenCodeCatalogTest {
    private val free = ModelEntry("dynamic-free", cost = ModelCost(0.0, 0.0))
    private val paid = ModelEntry("standard", cost = ModelCost(1.0, 2.0))
    private val response = ProviderListResponse(default = mapOf("opencode" to paid.id), all = listOf(
        ProviderEntry("anthropic", models = mapOf("other" to ModelEntry("other"))),
        ProviderEntry("opencode", models = listOf(paid, free).associateBy { it.id }),
    ), connected = listOf("opencode", "anthropic"))

    @Test fun `only connected OpenCode and prefer free over unrelated saved selection`() {
        assertEquals(listOf("opencode"), OpenCodeCatalog.providers(response).map { it.id })
        assertEquals(free, OpenCodeCatalog.choose(response, "anthropic", "other"))
        assertEquals(paid, OpenCodeCatalog.choose(response, "opencode", paid.id))
        assertEquals(free, OpenCodeCatalog.choose(response, "opencode", "removed-model"))
    }

    @Test fun `no implicit fallback to third party or disconnected catalog`() {
        assertNull(OpenCodeCatalog.choose(response.copy(connected = listOf("anthropic")), "", ""))
        assertTrue(OpenCodeCatalog.providers(response.copy(all = response.all.take(1))).isEmpty())
        assertFalse(OpenCodeCatalog.isFree(ModelEntry("unknown")))
    }

    @Test fun `parse server defaults and costs without hardcoded model names`() {
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<ProviderListResponse>(
            """{"all":[{"id":"opencode","models":{"new":{"id":"new","cost":{"input":0,"output":0,"cache":{}}}}}],"connected":["opencode"],"default":{"opencode":"new"}}"""
        )
        assertEquals("new", OpenCodeCatalog.choose(parsed, "", "")!!.id)
        assertTrue(OpenCodeCatalog.isFree(OpenCodeCatalog.choose(parsed, "", "")!!))
    }
}
