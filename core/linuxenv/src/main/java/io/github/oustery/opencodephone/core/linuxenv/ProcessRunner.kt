package io.github.oustery.opencodephone.core.linuxenv

import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Bounded output and cancellation/timeout cleanup, independent of Android. */
suspend fun runCapturedProcess(builder: ProcessBuilder, timeoutMs: Long, onLine: ((String) -> Unit)? = null): ExecResult =
    withContext(Dispatchers.IO) {
        val proc = builder.redirectErrorStream(true).start()
        val output = StringBuilder()
        val failure = AtomicReference<Throwable?>()
        val reader = Thread({
            try {
                proc.inputStream.bufferedReader().use { input ->
                    val chunk = CharArray(2048)
                    val line = StringBuilder()
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        synchronized(output) {
                            output.append(chunk, 0, n)
                            if (output.length > 20_000) output.delete(0, output.length - 20_000)
                        }
                        for (i in 0 until n) {
                            if (chunk[i] == '\n' || line.length >= 8192) {
                                onLine?.invoke(line.toString())
                                line.setLength(0)
                            }
                            if (chunk[i] != '\n') line.append(chunk[i])
                        }
                    }
                    if (line.isNotEmpty()) onLine?.invoke(line.toString())
                }
            } catch (e: Exception) { failure.set(e); proc.destroy() }
        }, "captured-process-output").apply { isDaemon = true }
        try {
            reader.start()
            val finished = runInterruptible { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            if (!finished) proc.destroy()
            runInterruptible { reader.join(2000) }
            failure.get()?.let { throw it }
            ExecResult(if (finished) proc.exitValue() else -1, synchronized(output) { output.toString() })
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                proc.destroy()
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    check(proc.waitFor(3, TimeUnit.SECONDS)) { "Child process did not terminate" }
                }
                proc.inputStream.close()
                proc.outputStream.close()
                proc.errorStream.close()
                reader.join(2000)
            }
        }
    }
