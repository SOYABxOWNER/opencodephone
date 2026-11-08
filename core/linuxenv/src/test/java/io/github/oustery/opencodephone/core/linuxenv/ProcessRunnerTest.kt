package io.github.oustery.opencodephone.core.linuxenv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ProcessRunnerTest {
    @Test fun `captures only bounded tail of long output`() = runBlocking {
        val result = runCapturedProcess(ProcessBuilder("sh", "-c", "head -c 2000000 /dev/zero | tr '\\0' x; printf final"), 10000)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.length <= 20000)
        assertTrue(result.output.endsWith("final"))
    }
    @Test fun `cancel reaps sleeping child`() = runBlocking {
        val pidFile = Files.createTempFile("child-pid", ".txt").toFile()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            runCapturedProcess(ProcessBuilder("sh", "-c", "echo \u0024\u0024 > '${pidFile.absolutePath}'; echo ready; exec sleep 60"), 60000) {
                if (it == "ready") started.complete(Unit)
            }
        }
        try {
            withTimeout(5000) { started.await() }
            val pid = pidFile.readText().trim()
            withTimeout(5000) { job.cancelAndJoin() }
            assertFalse(java.io.File("/proc/$pid").exists())
        } finally { job.cancelAndJoin(); pidFile.delete() }
    }
}
