package io.github.oustery.opencodephone.core.linuxenv

import android.os.Build
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

/**
 * One-time environment bootstrap pipeline:
 * download Debian rootfs (digest-pinned) → extract → install tools (git, curl)
 * → install the OpenCode CLI (SRI-verified) → configure.
 */
class BootstrapManager(
    val layout: EnvLayout,
    private val runtime: EnvironmentRuntime,
    private val scope: CoroutineScope,
    private val opencodeVersion: String = Pins.OPENCODE_VERSION,
) {

    private val registry = DockerRegistryClient(DockerRegistryClient.longTimeoutClient())
    private val installer = OpencodeInstaller(DockerRegistryClient.longTimeoutClient())

    private val _state = MutableStateFlow(BootstrapState())
    val state: StateFlow<BootstrapState> = _state

    private var job: Job? = null

    /** Phase the pipeline is currently in; kept so failures report the step. */
    private var currentPhase: BootstrapPhase = BootstrapPhase.NOT_STARTED

    fun start() {
        if (job?.isActive == true) return
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
            BootstrapState(phase = phase, label = label, progress = progress, message = message)
        }
    }

    private suspend fun run() {
        try {
            if (layout.isInstalled()) {
                setState(BootstrapPhase.READY, "Environment already installed")
                return
            }
            layout.ensureDirs()
            writeDnsFiles()

            val primary = Build.SUPPORTED_ABIS.firstOrNull()
                ?: error("Could not determine device ABI")
            val abi = DeviceAbi.fromPrimaryAbi(primary)
                ?: error("Unsupported CPU architecture: $primary")

            // 1. Debian rootfs, digest-pinned and checksum-verified.
            val digest = Pins.ROOTFS_MANIFEST_DIGESTS[abi.debianArch]
                ?: error("No pinned rootfs digest for ${abi.debianArch}")
            setState(
                BootstrapPhase.DOWNLOADING_ROOTFS,
                "Downloading Debian ${Pins.DEBIAN_SUITE} (${abi.debianArch})",
                message = "Manifest digest $digest",
            )
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

            setState(BootstrapPhase.EXTRACTING_ROOTFS, "Extracting root filesystem")
            RootfsExtractor.extract(archive, layout.rootfsDir)
            archive.delete()

            // Write DNS config into the rootfs as well; proot also bind-mounts
            // the app-side copies.
            File(layout.rootfsDir, "etc/resolv.conf").writeText(layout.resolvConf.readText())

            // 2. Base tooling inside the environment.
            setState(
                BootstrapPhase.INSTALLING_TOOLS,
                "Installing tools (git, curl, ripgrep)",
                message = "This may take several minutes on first run",
            )
            val toolsResult = runtime.exec(
                listOf(
                    "bash", "-lc",
                    "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                        "--no-install-recommends git curl ca-certificates ripgrep less procps",
                ),
                timeoutMs = 20 * 60_000,
            ) { line ->
                setState(
                    BootstrapPhase.INSTALLING_TOOLS,
                    "Installing tools (git, curl, ripgrep)",
                    message = line.take(120),
                )
            }
            if (toolsResult.exitCode != 0) {
                error("Tool installation failed (exit ${toolsResult.exitCode}): ${toolsResult.output.take(400)}")
            }

            // 3. OpenCode CLI.
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
            } else {
                setState(
                    BootstrapPhase.INSTALLING_OPENCODE,
                    "OpenCode CLI unavailable on this CPU",
                    message = "armv7 devices can still use Terminal and connect to a remote server in Settings",
                )
            }

            // 4. Final configuration.
            setState(BootstrapPhase.CONFIGURING, "Configuring environment")
            val configure = runtime.exec(
                listOf(
                    "bash", "-lc",
                    "mkdir -p /root/projects && " +
                        "git config --global init.defaultBranch main && " +
                        "git config --global core.editor true && " +
                        "git config --global advice.detachedHead false",
                ),
            )
            if (configure.exitCode != 0) {
                error("Configuration failed: ${configure.output.take(400)}")
            }

            layout.installedStamp.writeText("debian=${Pins.DEBIAN_SUITE};opencode=$opencodeVersion;abi=${abi.androidAbi}")
            setState(BootstrapPhase.READY, "Environment ready")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                BootstrapState(
                    phase = BootstrapPhase.FAILED,
                    label = "Bootstrap failed",
                    error = e.message ?: e.javaClass.simpleName,
                    failedPhase = currentPhase,
                )
            }
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
}
