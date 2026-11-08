package io.github.oustery.opencodephone.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderKeysCodecTest {

    @Test
    fun `roundtrip keys`() {
        val keys = mapOf("anthropic" to "sk-ant-123", "openai" to "sk-oai-456")
        val decoded = ProviderKeysCodec.decode(ProviderKeysCodec.encode(keys))
        assertEquals(keys, decoded)
    }

    @Test
    fun `empty and malformed input decode to empty map`() {
        assertEquals(emptyMap<String, String>(), ProviderKeysCodec.decode(""))
        assertEquals(emptyMap<String, String>(), ProviderKeysCodec.decode("not json"))
        assertEquals(emptyMap<String, String>(), ProviderKeysCodec.decode("[]"))
    }
}
