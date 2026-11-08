package io.github.oustery.opencodephone.core.opencode

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class CancellableHttpTest {
    @Test fun `cancellation closes a call waiting for response headers`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/slow")).build())
            val job = launch { call.consumeCancellable { it.body?.string() } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            withTimeout(5000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }
}
