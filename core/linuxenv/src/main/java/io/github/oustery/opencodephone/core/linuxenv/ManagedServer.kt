package io.github.oustery.opencodephone.core.linuxenv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Credentials are part of process identity: editing them must restart the server. */
data class ServerLaunchConfig(val port: Int, val username: String?, val password: String?)

/** Process lifecycle independent of Android, so readiness and exit races can be tested. */
class ManagedServer(
    private val launch: (ServerLaunchConfig) -> Process,
    private val probe: suspend (ServerLaunchConfig) -> Unit,
) {
    private val startLock = Mutex()
    private val guard = Any()
    private var generation = 0L
    private var process: Process? = null
    private var config: ServerLaunchConfig? = null
    private val lines = ArrayDeque<String>()
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log

    private fun log(proc: Process, line: String) = synchronized(guard) {
        if (process === proc) {
            lines.addLast(line)
            while (lines.size > 200) lines.removeFirst()
            _log.value = lines.joinToString("\n")
        }
    }

    suspend fun start(options: ServerLaunchConfig, timeoutMs: Long = 60_000, restart: Boolean = false) =
        withContext(Dispatchers.IO) {
            startLock.withLock {
                val reuse = synchronized(guard) {
                    !restart && config == options && process?.isAlive == true && _state.value is ServerState.Running
                }
                if (reuse) return@withLock
                val (ticket, previous) = synchronized(guard) {
                    generation++
                    val old = process
                    process = null
                    config = null
                    lines.clear()
                    _log.value = ""
                    _state.value = ServerState.Starting("Starting OpenCode on :${options.port}")
                    generation to old
                }
                previous?.let { terminate(it) }
                if (synchronized(guard) { generation != ticket }) return@withLock
                val proc = try {
                    require(options.port in 1..65535) { "Port must be between 1 and 65535" }
                    launch(options)
                } catch (e: CancellationException) {
                    synchronized(guard) {
                        if (generation == ticket) _state.value = ServerState.Stopped
                    }
                    throw e
                } catch (e: Exception) {
                    synchronized(guard) {
                        if (generation == ticket) _state.value = ServerState.Failed("Cannot start OpenCode: ${e.message}")
                    }
                    return@withLock
                }
                val accepted = synchronized(guard) {
                    if (ticket != generation) false else {
                        process = proc
                        config = options
                        true
                    }
                }
                if (!accepted) {
                    terminate(proc)
                    return@withLock
                }
                Thread({
                    try {
                        proc.inputStream.bufferedReader().useLines { output -> output.forEach { log(proc, it) } }
                    } catch (_: Exception) {
                        // Stream closure on stop is expected.
                    }
                    proc.waitFor()
                    synchronized(guard) {
                        if (process === proc) {
                            process = null
                            config = null
                            _state.value = ServerState.Failed("OpenCode exited (${proc.exitValue()}). ${_log.value.takeLast(1500)}")
                        }
                    }
                }, "opencode-output").apply { isDaemon = true; start() }

                var lastFailure = "API did not respond"
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                try {
                    currentCoroutineContext().ensureActive()
                    while (System.nanoTime() < deadline && proc.isAlive && synchronized(guard) { process === proc }) {
                        try {
                            // A 200 HTML page is not readiness. Probe must parse and validate health JSON.
                            probe(options)
                            val running = synchronized(guard) {
                                if (process === proc && proc.isAlive) {
                                    _state.value = ServerState.Running(options.port)
                                    true
                                } else false
                            }
                            if (running) return@withLock
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastFailure = e.message ?: e.javaClass.simpleName
                        }
                        delay(300)
                    }
                    synchronized(guard) {
                        if (process === proc) {
                            process = null
                            config = null
                            _state.value = ServerState.Failed(
                                "OpenCode API unavailable: $lastFailure. ${_log.value.takeLast(1500)}",
                            )
                        }
                    }
                    terminate(proc)
                } catch (e: CancellationException) {
                    synchronized(guard) {
                        if (process === proc) {
                            process = null
                            config = null
                            _state.value = ServerState.Stopped
                        }
                    }
                    terminate(proc)
                    throw e
                }
            }
        }

    fun stop() {
        val proc = synchronized(guard) {
            generation++
            val old = process
            process = null
            config = null
            _state.value = ServerState.Stopped
            old
        }
        if (proc != null) terminate(proc)
    }

    private fun terminate(proc: Process) {
        runCatching {
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    fun fail(message: String) {
        stop()
        _state.value = ServerState.Failed(message)
    }

    fun isAlive(): Boolean = synchronized(guard) { process?.isAlive == true }
}
