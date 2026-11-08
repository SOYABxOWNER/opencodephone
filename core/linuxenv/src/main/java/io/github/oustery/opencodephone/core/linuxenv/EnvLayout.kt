package io.github.oustery.opencodephone.core.linuxenv

import android.content.Context
import java.io.File

/**
 * Filesystem layout of the embedded Linux environment.
 *
 * baseDir/
 *   rootfs/       — extracted Debian root tree
 *   workspace/    — user projects (bind-mounted into the env at /root/projects,
 *                   also directly readable/writable by the app)
 *   downloads/    — temporary downloads during bootstrap
 *   tmp/          — proot temporary dir
 *   resolv.conf, hosts — bind-mounted into the env
 *   .installed    — marker written after a successful bootstrap
 */
class EnvLayout(private val context: Context) {

    val baseDir: File = File(context.filesDir, "linuxenv")
    val workspaceDir: File = File(context.filesDir, "workspace")

    val rootfsDir: File get() = File(baseDir, "rootfs")
    val downloadDir: File get() = File(baseDir, "downloads")
    val tmpDir: File get() = File(baseDir, "tmp")
    val resolvConf: File get() = File(baseDir, "resolv.conf")
    val hostsFile: File get() = File(baseDir, "hosts")
    val installedStamp: File get() = File(baseDir, ".installed")

    /** Append-only log of the latest bootstrap run (for diagnostics). */
    val bootstrapLogFile: File get() = File(baseDir, "bootstrap.log")

    /** Location of the opencode CLI inside the root filesystem. */
    fun opencodeBin(): File = File(rootfsDir, "usr/local/bin/opencode")

    /** proot binary bundled with the APK for the current ABI. */
    fun prootBin(): File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    fun ensureDirs() {
        baseDir.mkdirs()
        workspaceDir.mkdirs()
        downloadDir.mkdirs()
        tmpDir.mkdirs()
    }

    /** True when the environment finished bootstrapping. */
    fun isInstalled(): Boolean = installedStamp.exists() && File(rootfsDir, "bin").exists()
}
