package io.github.oustery.opencodephone.core.model

/** /provider includes the full external catalog. Only connected OpenCode is usable here. */
object OpenCodeCatalog {
    fun providers(response: ProviderListResponse): List<ProviderEntry> = response.all.filter {
        it.id == "opencode" && it.id in response.connected && it.models.isNotEmpty()
    }

    fun isFree(model: ModelEntry): Boolean = model.cost?.input == 0.0 && model.cost.output == 0.0

    fun choose(response: ProviderListResponse, savedProvider: String, savedModel: String): ModelEntry? {
        val models = providers(response).firstOrNull()?.models ?: return null
        if (savedProvider == "opencode") models[savedModel]?.let { return it }
        val preferred = response.default["opencode"]?.let { models[it] }
        if (preferred != null && isFree(preferred)) return preferred
        return models.values.filter(::isFree).sortedBy { it.id }.firstOrNull()
            ?: preferred ?: models.values.sortedBy { it.id }.firstOrNull()
    }
}
