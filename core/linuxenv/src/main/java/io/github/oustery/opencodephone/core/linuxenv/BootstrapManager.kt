package io.github.oustery.opencodephone.core.linuxenv

import android.os.Build
import android.system.Os
import android.system.OsConstants
import io.github.oustery.opencodephone.core.model.BootstrapPhase
import io.github.oustery.opencodephone.core.model.BootstrapState
import io.github.oustery.opencodephone.core.model.DeviceAbi
import io.github.oustery.opencodephone.core.model.Pins
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-time environment bootstrap pipeline:
 * download Debian rootfs (digest-pinned) → extract → preflight probe →
 * install the pre-resolved toolset (app-side extraction, no guest processes)
 * → install the OpenCode CLI (SRI-verified) → configure.
 *
 * The toolset phase deliberately does NOT run apt-get inside proot: Android
 * 12+ kills high-CPU child processes of apps (phantom process killer), which
 * surfaced as `exit 255` with empty output during the silent
 * `apt-get update` decompression phase. Everything heavy is now downloaded
 * and extracted by the app itself; only short, low-CPU commands (probe,
 * git config) still run inside the environment (see ADR-008).
 */
class BootstrapManager(
    val layout: EnvLayout,
    private val runtime: EnvironmentRuntime,
    private val scope: CoroutineScope,
    private var opencodeVersion: String = Pins.OPENCODE_VERSION,
) {

    private val registry = DockerRegistryClient(DockerRegistryClient.longTimeoutClient())
    private val installer = OpencodeInstaller(DockerRegistryClient.longTimeoutClient())
    private val toolset = ToolsetInstaller(DockerRegistryClient.longTimeoutClient())

    private val _state = MutableStateFlow(BootstrapState())
    val state: StateFlow<BootstrapState> = _state

    private var job: Job? = null

    /** Phase the pipeline is currently in; kept so failures report the step. */
    private var currentPhase: BootstrapPhase = BootstrapPhase.NOT_STARTED

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun start(version: String = opencodeVersion) {
        if (job?.isActive == true) return
        opencodeVersion = version
        job = scope.launch(Dispatchers.IO) { run() }
    }

    fun retry() {
        job?.cancel()
        job = null
        _state.value = BootstrapState()
        start()
    }

    /** Delete the whole environment (workspace is preserved). */
    fun reset() {
        job?.cancel()
        job = null
        runtime.stopServer()
        layout.baseDir.deleteRecursively()
        layout.installedStamp.delete()
        _state.value = BootstrapState()
    }

    private fun setState(
        phase: BootstrapPhase,
        label: String = "",
        progress: Float? = null,
        message: String = "",
    ) {
        currentPhase = phase
        _state.update {
            it.copy(phase = phase, label = label, progress = progress, message = message)
        }
    }

    private fun log(line: String) {
        val stamped = "[${dateFormat.format(Date())}] $line"
        _state.update { it.copy(log = (it.log + stamped).takeLast(LOG_LIMIT)) }
        runCatching {
            layout.bootstrapLogFile.appendText(stamped + "\n")
        }
    }

    private suspend fun run() {
        try {
            if (layout.isInstalled()) {
                // Older APKs installed an incompatible V2 CLI. Upgrade only
                // that binary; never delete Debian or the user's workspace.
                val stamp = layout.installedStamp.readText()
                if ("opencode=$opencodeVersion" !in stamp.split(';')) {
                    layout.ensureDirs()
                    installOpenCode(deviceAbi())
                    layout.installedStamp.writeText(
                        "debian=${Pins.DEBIAN_SUITE};opencode=$opencodeVersion;abi=${deviceAbi().androidAbi}",
                    )
                }
                setState(BootstrapPhase.READY, "Environment ready")
                return
            }
            layout.ensureDirs()
            writeDnsFiles()

            val primary = Build.SUPPORTED_ABIS.firstOrNull()
                ?: error("Could not determine device ABI")
            val abi = DeviceAbi.fromPrimaryAbi(primary)
                ?: error("Unsupported CPU architecture: $primary")

            log("device: ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                "ABI $primary, page ${pageSize()} bytes")
            log("bootstrap: rootfs=${Pins.DEBIAN_SUITE}, opencode=$opencodeVersion, proot=${Pins.PROOT_VERSION}")

            // 1. Debian rootfs, digest-pinned and checksum-verified.
            val digest = Pins.ROOTFS_MANIFEST_DIGESTS[abi.debianArch]
                ?: error("No pinned rootfs digest for ${abi.debianArch}")
            setState(
                BootstrapPhase.DOWNLOADING_ROOTFS,
                "Downloading Debian ${Pins.DEBIAN_SUITE} (${abi.debianArch})",
                message = "Manifest digest $digest",
            )
            log("downloading rootfs manifest $digest")
            val manifest = registry.fetchManifest(digest)
            val layer = registry.singleLayer(manifest)
            val archive = File(layout.downloadDir, "rootfs.tar.gz")
            registry.downloadLayer(layer, archive) { read, total ->
                val fraction = if (total > 0) read.toFloat() / total else null
                setState(
                    BootstrapPhase.DOWNLOADING_ROOTFS,
                    "Downloading Debian ${Pins.DEBIAN_SUITE} (${abi.debianArch})",
                    progress = fraction,
                    message = "${read / (1024 * 1024)} / ${maxOf(total, 0) / (1024 * 1024)} MB",
                )
            }
            log("rootfs downloaded: ${archive.length() / (1024 * 1024)} MB")

            setState(BootstrapPhase.EXTRACTING_ROOTFS, "Extracting root filesystem")
            RootfsExtractor.extract(archive, layout.rootfsDir)
            archive.delete()
            log("rootfs extracted to ${layout.rootfsDir.absolutePath}")

            // Write DNS config into the rootfs as well; proot also bind-mounts
            // the app-side copies.
            File(layout.rootfsDir, "etc/resolv.conf").writeText(layout.resolvConf.readText())

            // 2. Preflight probe: can proot execute anything at all? This runs
            // before any further downloads so environmental failures (SELinux,
            // process killers, broken extraction) are reported clearly.
            setState(BootstrapPhase.EXTRACTING_ROOTFS, "Checking the environment")
            log("probe: running /bin/true inside the environment")
            val probe = runtime.exec(
                listOf("/bin/true"),
                timeoutMs = 60_000,
                // Level 1 reports terminating signals; without it proot can
                // silently return 255 for SIGSYS as well as SIGKILL.
                prootVerbosity = 1,
                onLine = { log("probe: $it") },
            )
            if (probe.exitCode != 0) {
                log("probe failed: exit ${probe.exitCode}, output: ${probe.output.takeLast(2000)}")
                error(
                    "Environment self-check failed (exit ${probe.exitCode}). " + explainExitCode(probe.exitCode) +
                        "\n" + probe.output.takeLast(2000),
                )
            }
            log("probe ok")

            // 3. Base tooling: app-side toolset extraction (no apt inside the
            // environment — see class doc).
            setState(
                BootstrapPhase.INSTALLING_TOOLS,
                "Installing tools (git, curl, ripgrep)",
                message = "Downloading the toolset package",
            )
            toolset.install(layout, abi.debianArch) { read, total ->
                val fraction = if (total > 0) read.toFloat() / total else null
                setState(
                    BootstrapPhase.INSTALLING_TOOLS,
                    "Installing tools (git, curl, ripgrep)",
                    progress = fraction,
                    message = "${read / (1024 * 1024)} / ${maxOf(total, 0) / (1024 * 1024)} MB",
                )
            }
            log("toolset ${abi.debianArch} installed")

            // Verify the tools actually run (short, low-CPU command).
            val check = runtime.exec(
                listOf("bash", "-lc", "git --version && curl --version | head -1"),
                timeoutMs = 60_000,
            )
            if (check.exitCode != 0) {
                log("tool check failed: exit ${check.exitCode}, output: ${check.output.take(300)}")
                error("Installed tools do not run (exit ${check.exitCode}). " + explainExitCode(check.exitCode))
            }
            log("tool check: ${check.output.trim().lineSequence().firstOrNull().orEmpty()}")

            // 4. Install the CLI matching the client protocol.
            installOpenCode(abi)

            // 5. Final configuration (short, low-CPU guest command).
            setState(BootstrapPhase.CONFIGURING, "Configuring environment")
            val configure = runtime.exec(
                listOf(
                    "bash", "-lc",
                    "mkdir -p /root/projects && " +
                        "git config --global init.defaultBranch main && " +
                        "git config --global core.editor true && " +
                        "git config --global advice.detachedHead false",
                ),
                timeoutMs = 60_000,
            )
            if (configure.exitCode != 0) {
                log("configure failed: exit ${configure.exitCode}, output: ${configure.output.take(300)}")
                error("Configuration failed (exit ${configure.exitCode}). " + explainExitCode(configure.exitCode))
            }

            layout.installedStamp.writeText(
                "debian=${Pins.DEBIAN_SUITE};opencode=$opencodeVersion;abi=${abi.androidAbi}",
            )
            log("bootstrap complete")
            setState(BootstrapPhase.READY, "Environment ready")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("FAILED: ${e.message?.take(300)}")
            _state.update {
                BootstrapState(
                    phase = BootstrapPhase.FAILED,
                    label = "Bootstrap failed",
                    error = e.message ?: e.javaClass.simpleName,
                    failedPhase = currentPhase,
                    log = it.log,
                )
            }
        }
    }

    private fun deviceAbi(): DeviceAbi = DeviceAbi.fromPrimaryAbi(
        Build.SUPPORTED_ABIS.firstOrNull() ?: error("Could not determine device ABI"),
    ) ?: error("Unsupported CPU architecture")

    private suspend fun installOpenCode(abi: DeviceAbi) {
        val target = abi.opencodeTarget
        if (target != null) {
            setState(
                BootstrapPhase.INSTALLING_OPENCODE,
                "Installing OpenCode CLI $opencodeVersion",
            )
            val info = installer.resolvePackage(target, opencodeVersion)
            val tgz = File(layout.downloadDir, "opencode-$opencodeVersion.tgz")
            installer.downloadTarball(info, tgz) { read, total ->
                val fraction = if (total > 0) read.toFloat() / total else null
                setState(
                    BootstrapPhase.INSTALLING_OPENCODE,
                    "Installing OpenCode CLI $opencodeVersion",
                    progress = fraction,
                    message = "${read / (1024 * 1024)} / ${maxOf(total, 0) / (1024 * 1024)} MB",
                )
            }
            val bin = layout.opencodeBin()
            installer.extractBinary(tgz, bin)
            tgz.delete()
            val versionResult = runtime.exec(listOf("opencode", "--version"), timeoutMs = 60_000)
            check(versionResult.exitCode == 0 && versionResult.output.contains(opencodeVersion)) {
                "OpenCode CLI verification failed (exit ${versionResult.exitCode}): ${versionResult.output.takeLast(1000)}"
            }
            log("opencode CLI installed: ${bin.absolutePath}, version ${versionResult.output.trim()}")
        } else {
            setState(
                BootstrapPhase.INSTALLING_OPENCODE,
                "OpenCode CLI unavailable on this CPU",
                message = "armv7 devices can still use Terminal and connect to a remote server in Settings",
            )
            log("opencode CLI skipped: no build for armv7")
        }

    }

    private fun writeDnsFiles() {
        layout.resolvConf.writeText(
            """
            nameserver 8.8.8.8
            nameserver 1.1.1.1
            options edns0 trust-ad
            """.trimIndent() + "\n",
        )
        layout.hostsFile.writeText("127.0.0.1 localhost\n")
    }

    /** Kernel page size, useful in diagnostics (16 KB devices). */
    private fun pageSize(): Long = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE)
    }.getOrDefault(-1L)

    companion object {
        private const val LOG_LIMIT = 60

        /**
         * Human-readable explanation for a guest command exit code. proot
         * reports the last normal guest exit status. Its initial -1 becomes
         * 255 if no guest exits normally; this does not identify the signal
         * or prove Android's phantom process killer was involved.
         */
        fun explainExitCode(code: Int): String = when (code) {
            255 -> "PRoot did not report a normal guest exit. This can result from a blocked " +
                "syscall (SIGSYS/seccomp), a crash, or Android killing the process. " +
                "Check the probe log for the terminating signal. For SIGKILL, keep the app " +
                "on screen and disable battery optimization; battery settings do not fix SIGSYS."
            137 -> "The process was killed (SIGKILL), most likely by Android to save memory. " +
                "Close other apps and try again."
            126 -> "A binary exists but cannot be executed — file permissions inside the " +
                "environment are broken. Retry the setup to re-extract."
            127 -> "A required binary was not found — the environment is incomplete. " +
                "Retry the setup to re-extract."
            139 -> "The process crashed (SIGSEGV). This may indicate an incompatible CPU " +
                "or a system restriction on executing app-provided binaries."
            -1 -> "The command timed out."
            else -> "The command inside the environment failed. See the log below for its output."
        }
    }
}
