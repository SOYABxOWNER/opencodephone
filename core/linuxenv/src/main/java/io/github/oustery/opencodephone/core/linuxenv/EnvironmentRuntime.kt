package io.github.oustery.opencodephone.core.linuxenv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ExecResult(
    val exitCode: Int,
    val output: String,
)

sealed interface ServerState {
    data object Stopped : ServerState
    data class Starting(val message: String) : ServerState
    data class Running(val port: Int) : ServerState
    data class Failed(val message: String) : ServerState
}

/**
 * Runs processes inside the Debian environment through proot and manages the
 * long-running `opencode serve` child process.
 */
class EnvironmentRuntime(
    private val layout: EnvLayout,
) {

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = _serverState

    private val logLines = ArrayDeque<String>()
    private val _serverLog = MutableStateFlow("")
    val serverLog: StateFlow<String> = _serverLog

    @Volatile
    private var serverProcess: Process? = null

    @Volatile
    var serverPort: Int = 4096
        private set

    private fun appendLog(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 200) logLines.removeFirst()
            _serverLog.value = logLines.joinToString("\n")
        }
    }

    /** Build a ProcessBuilder that runs [command] inside the environment. */
    fun process(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: String = "/root",
        mergeOutput: Boolean = false,
        prootVerbosity: Int = 0,
    ): ProcessBuilder {
        val argv = PRootCommand.build(
            rootfsDir = layout.rootfsDir,
            prootBin = layout.prootBin(),
            workspaceDir = layout.workspaceDir,
            resolvConf = layout.resolvConf,
            hostsFile = layout.hostsFile,
            command = command,
            cwd = cwd,
            verbosity = prootVerbosity,
        )
        val pb = ProcessBuilder(argv)
        val environ = pb.environment()
        environ.putAll(PRootCommand.baseEnvironment(layout.tmpDir))
        environ.putAll(env)
        if (mergeOutput) pb.redirectErrorStream(true)
        return pb
    }

    /**
     * Run a one-shot command inside the environment and capture merged output.
     */
    suspend fun exec(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: String = "/root",
        timeoutMs: Long = 10 * 60_000,
        onLine: ((String) -> Unit)? = null,
        prootVerbosity: Int = 0,
    ): ExecResult = withContext(Dispatchers.IO) {
        val proc = process(command, env, cwd, mergeOutput = true, prootVerbosity = prootVerbosity).start()
        val output = StringBuilder()
        val reader = Thread {
            proc.inputStream.bufferedReader().forEachLine { line ->
                synchronized(output) { output.appendLine(line) }
                onLine?.invoke(line)
            }
        }
        reader.start()
        // waitFor() without a deadline blocks the IO thread and cannot be
        // interrupted by withTimeoutOrNull. Use the native timed wait instead.
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroy()
            proc.waitFor(3, TimeUnit.SECONDS)
            proc.destroyForcibly()
        }
        reader.join(2000)
        ExecResult(
            exitCode = if (finished) proc.exitValue() else -1,
            output = synchronized(output) { output.toString() }.takeLast(20_000),
        )
    }

    /**
     * Start `opencode serve` as a child process and wait until the HTTP API
     * answers. Returns when healthy or throws after [timeoutMs].
     */
    suspend fun startServer(
        port: Int,
        password: String?,
        username: String?,
        timeoutMs: Long = 45_000,
    ): Unit = withContext(Dispatchers.IO) {
        val current = serverProcess
        if (current?.isAlive == true && _serverState.value is ServerState.Running) {
            if (serverPort != port) stopServer() else return@withContext
        }
        stopServer()
        serverPort = port
        _serverState.value = ServerState.Starting("Starting opencode serve on :$port")

        val env = buildMap<String, String> {
            if (!password.isNullOrBlank()) {
                put("OPENCODE_SERVER_PASSWORD", password)
                put("OPENCODE_SERVER_USERNAME", username ?: "opencode")
            }
        }
        val command = listOf(
            "opencode", "serve",
            "--hostname", "127.0.0.1",
            "--port", port.toString(),
        )
        val proc = process(command, env, cwd = PRootCommand.WORKSPACE_MOUNT, mergeOutput = true).start()
        serverProcess = proc

        val watcher = Thread {
            proc.inputStream.bufferedReader().forEachLine { line ->
                appendLog(line)
            }
        }
        watcher.start()

        val deadline = System.currentTimeMillis() + timeoutMs
        val healthy = AtomicBoolean(false)
        while (System.currentTimeMillis() < deadline && proc.isAlive) {
            if (probeHealth(port, password, username)) {
                healthy.set(true)
                break
            }
            delay(1000)
        }
        if (healthy.get()) {
            _serverState.value = ServerState.Running(port)
        } else {
            proc.destroyForcibly()
            _serverState.value = ServerState.Failed(
                "opencode serve did not become healthy within ${timeoutMs / 1000}s. " +
                    "Log tail: ${synchronized(logLines) { logLines.takeLast(8).joinToString(" | ") }}",
            )
        }
    }

    /** Direct HTTP probe against the local server health endpoint. */
    private suspend fun probeHealth(port: Int, password: String?, username: String?): Boolean = try {
        val url = "http://127.0.0.1:$port/global/health"
        val builder = okhttp3.Request.Builder().url(url)
        if (!password.isNullOrBlank()) {
            builder.header(
                "Authorization",
                okhttp3.Credentials.basic(username ?: "opencode", password),
            )
        }
        withContext(Dispatchers.IO) {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
                .newCall(builder.build())
                .execute()
                .use { it.isSuccessful }
        }
    } catch (_: Exception) {
        false
    }

    fun stopServer() {
        val proc = serverProcess ?: return
        serverProcess = null
        try {
            proc.destroy()
            proc.waitFor(3, TimeUnit.SECONDS)
            if (proc.isAlive) proc.destroyForcibly()
        } catch (_: Exception) {
        }
        _serverState.value = ServerState.Stopped
    }

    /** True when the serve process is alive (regardless of health probe). */
    fun isServerAlive(): Boolean = serverProcess?.isAlive == true
}
