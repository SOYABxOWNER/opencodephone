package io.github.oustery.opencodephone.core.linuxenv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

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

    private val interactiveGuard = Any()
    private val interactive = mutableSetOf<Process>()
    private var interactiveBlocked = false

    fun startInteractive(builder: ProcessBuilder): Process = synchronized(interactiveGuard) {
        check(!interactiveBlocked && layout.isInstalled()) { "Environment is not ready" }
        interactive.removeAll { !it.isAlive }
        builder.start().also { interactive.add(it) }
    }

    fun stopInteractive() = synchronized(interactiveGuard) {
        interactiveBlocked = true
        interactive.forEach { proc ->
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) { proc.destroyForcibly(); proc.waitFor(3, TimeUnit.SECONDS) }
        }
        interactive.clear()
    }

    fun resumeInteractive() = synchronized(interactiveGuard) { interactiveBlocked = false }

    private val managedServer = ManagedServer(
        launch = { cfg ->
            check(layout.isInstalled() && layout.opencodeBin().isFile) {
                "Local CLI is not installed on this device. Finish setup or select a remote V1 server."
            }
            val env = localServerEnvironment(cfg)
            process(
                listOf("opencode", "serve", "--hostname", "127.0.0.1", "--port", cfg.port.toString()),
                env = env, cwd = PRootCommand.WORKSPACE_MOUNT, mergeOutput = true,
            ).start()
        },
        probe = { cfg ->
            io.github.oustery.opencodephone.core.opencode.OpenCodeClient(
                client = localProbeClient,
                config = io.github.oustery.opencodephone.core.opencode.ConnectionConfig(
                    "127.0.0.1", cfg.port, cfg.username, cfg.password,
                ),
            ).health()
            Unit
        },
    )
    private val localProbeClient = okhttp3.OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()
    val serverState: StateFlow<ServerState> = managedServer.state
    val serverLog: StateFlow<String> = managedServer.log

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
    ): ExecResult = runCapturedProcess(
        process(command, env, cwd, mergeOutput = true, prootVerbosity = prootVerbosity), timeoutMs, onLine,
    )

    suspend fun startServer(
        port: Int,
        password: String?,
        username: String?,
        timeoutMs: Long = 60_000,
        restart: Boolean = false,
    ) = managedServer.start(ServerLaunchConfig(port, username, password), timeoutMs, restart)

    fun reportServerFailure(message: String) = managedServer.fail(message)

    fun stopServer() = managedServer.stop()

    fun isServerAlive(): Boolean = managedServer.isAlive()
}
