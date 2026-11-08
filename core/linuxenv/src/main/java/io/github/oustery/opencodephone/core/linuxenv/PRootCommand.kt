package io.github.oustery.opencodephone.core.linuxenv

import java.io.File

/**
 * Builds proot invocation command lines and environments.
 *
 * proot runs the Debian root tree without root privileges by intercepting
 * syscalls with ptrace. The workspace directory is bind-mounted at
 * `/root/projects` so the app and the environment share the same files.
 */
object PRootCommand {

    const val WORKSPACE_MOUNT: String = "/root/projects"

    fun build(
        rootfsDir: File,
        prootBin: File,
        workspaceDir: File,
        resolvConf: File,
        hostsFile: File,
        command: List<String>,
        cwd: String = "/root",
        extraBinds: List<Pair<String, String>> = emptyList(),
        verbosity: Int = 0,
    ): List<String> = buildList {
        add(prootBin.absolutePath)
        add("--kill-on-exit")
        if (verbosity > 0) {
            add("-v")
            add(verbosity.toString())
        }
        add("-r")
        add(rootfsDir.absolutePath)
        add("-w")
        add(cwd)
        add("-b")
        add("/proc")
        add("-b")
        add("/sys")
        add("-b")
        add("/dev")
        add("-b")
        add("${workspaceDir.absolutePath}:$WORKSPACE_MOUNT")
        add("-b")
        add("${resolvConf.absolutePath}:/etc/resolv.conf")
        add("-b")
        add("${hostsFile.absolutePath}:/etc/hosts")
        extraBinds.forEach { (src, dst) ->
            add("-b")
            add("$src:$dst")
        }
        // Fake root identity so apt/dpkg/user tooling behaves normally.
        add("-0")
        addAll(command)
    }

    fun baseEnvironment(tmpDir: File): Map<String, String> = mapOf(
        "HOME" to "/root",
        // V2 uses an incompatible database/config layout. Preserve its data
        // but keep the supported V1 CLI in an isolated XDG namespace.
        "XDG_DATA_HOME" to "/root/.local/share/opencodephone-v1",
        "XDG_STATE_HOME" to "/root/.local/state/opencodephone-v1",
        "XDG_CACHE_HOME" to "/root/.cache/opencodephone-v1",
        "XDG_CONFIG_HOME" to "/root/.config/opencodephone-v1",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        "SHELL" to "/bin/bash",
        "PROOT_NO_SECCOMP" to "1",
        // Debian glibc registers rseq before main(), even for /bin/true.
        // Android's inherited app seccomp filter can reject it with SIGSYS;
        // PROOT_NO_SECCOMP disables only proot's own filter, not Android's.
        "GLIBC_TUNABLES" to "glibc.pthread.rseq=0",
        "PROOT_TMP_DIR" to tmpDir.absolutePath,
    )
}
