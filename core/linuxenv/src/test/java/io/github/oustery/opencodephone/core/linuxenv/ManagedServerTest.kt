package io.github.oustery.opencodephone.core.linuxenv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ManagedServerTest {
    private val cfg = ServerLaunchConfig(4096, "opencode", "")
    private fun sleeper() = ProcessBuilder("sh", "-c", "exec sleep 30").redirectErrorStream(true).start()

    @Test
    fun `same configuration reuses process but credentials force restart`() = runBlocking {
        val count = AtomicInteger()
        val server = ManagedServer({ count.incrementAndGet(); sleeper() }, { })
        try {
            server.start(cfg)
            assertTrue(server.state.value is ServerState.Running)
            server.start(cfg)
            assertEquals(1, count.get())
            server.start(cfg.copy(password = "changed"))
            assertEquals(2, count.get())
            assertTrue(server.state.value is ServerState.Running)
        } finally { server.stop() }
        assertEquals(ServerState.Stopped, server.state.value)
        assertFalse(server.isAlive())
    }

    @Test
    fun `HTTP success without valid health never marks running`() = runBlocking {
        val server = ManagedServer({ sleeper() }, { error("HTML is not API health") })
        try {
            server.start(cfg, timeoutMs = 100)
            val failure = server.state.value as ServerState.Failed
            assertTrue(failure.message.contains("HTML is not API health"))
            assertFalse(server.isAlive())
        } finally { server.stop() }
    }

    @Test
    fun `launch exception becomes visible failure`() = runBlocking {
        val server = ManagedServer({ error("Permission denied") }, { })
        server.start(cfg)
        assertTrue((server.state.value as ServerState.Failed).message.contains("Permission denied"))
    }

    @Test
    fun `process death after readiness updates state and retains logs`() = runBlocking {
        val server = ManagedServer({
            ProcessBuilder("sh", "-c", "sleep 0.3; echo server-crash; exit 7")
                .redirectErrorStream(true).start()
        }, { })
        try {
            server.start(cfg)
            val failed = withTimeout(5000) { server.state.first { it is ServerState.Failed } } as ServerState.Failed
            assertTrue(failed.message.contains("7"))
            assertTrue(server.log.value.contains("server-crash"))
        } finally { server.stop() }
    }

    @Test
    fun `cancel startup terminates child instead of leaving starting forever`() = runBlocking {
        val launched = CompletableDeferred<Process>()
        val server = ManagedServer({ sleeper().also { launched.complete(it) } }, { awaitCancellation() })
        val start = async { server.start(cfg) }
        val process = withTimeout(5000) { launched.await() }
        start.cancelAndJoin()
        assertFalse(process.isAlive)
        assertEquals(ServerState.Stopped, server.state.value)
    }
}
