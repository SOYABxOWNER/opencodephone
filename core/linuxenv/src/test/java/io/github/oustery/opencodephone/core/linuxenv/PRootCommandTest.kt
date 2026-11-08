package io.github.oustery.opencodephone.core.linuxenv

import io.github.oustery.opencodephone.core.model.DeviceAbi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PRootCommandTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    @Test
    fun `builds command with binds and fake root`() {
        val base = tempDir("proot-test")
        val rootfs = File(base, "rootfs")
        val workspace = File(base, "workspace")
        val resolv = File(base, "resolv.conf")
        val hosts = File(base, "hosts")
        val proot = File("/fake/bin/libproot.so")

        val argv = PRootCommand.build(
            rootfsDir = rootfs,
            prootBin = proot,
            workspaceDir = workspace,
            resolvConf = resolv,
            hostsFile = hosts,
            command = listOf("bash", "-lc", "ls -la"),
            cwd = "/root/projects",
        )

        assertEquals(proot.absolutePath, argv.first())
        assertTrue(argv.contains("--kill-on-exit"))
        assertTrue(argv.contains("-r"))
        assertEquals(rootfs.absolutePath, argv[argv.indexOf("-r") + 1])
        assertTrue(argv.contains("-0"))
        assertEquals(listOf("bash", "-lc", "ls -la"), argv.takeLast(3))
        // workspace bind present
        assertTrue(argv.contains("${workspace.absolutePath}:${PRootCommand.WORKSPACE_MOUNT}"))
        // cwd
        assertEquals("/root/projects", argv[argv.indexOf("-w") + 1])
    }

    @Test
    fun `environment contains path and dns-safe flags`() {
        val env = PRootCommand.baseEnvironment(File("/tmp/proot-tmp"))
        assertEquals("/root", env["HOME"])
        assertEquals("/root/.local/share/opencodephone-v1", env["XDG_DATA_HOME"])
        assertEquals("/root/.config/opencodephone-v1", env["XDG_CONFIG_HOME"])
        assertTrue(env["PATH"]!!.contains("/usr/local/bin"))
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals("glibc.pthread.rseq=0", env["GLIBC_TUNABLES"])
        assertEquals("C.UTF-8", env["LANG"])
    }

    @Test
    fun `probe verbosity reports terminating signals without changing guest command`() {
        val argv = PRootCommand.build(
            File("/rootfs"), File("/libproot.so"), File("/workspace"),
            File("/resolv.conf"), File("/hosts"), listOf("/bin/true"),
            verbosity = 1,
        )
        assertEquals("1", argv[argv.indexOf("-v") + 1])
        assertEquals("/bin/true", argv.last())
    }

    @Test
    fun `abi mapping covers supported devices`() {
        assertEquals(DeviceAbi.ARM64, DeviceAbi.fromPrimaryAbi("arm64-v8a"))
        assertEquals(DeviceAbi.ARM32, DeviceAbi.fromPrimaryAbi("armeabi-v7a"))
        assertEquals(DeviceAbi.X86_64, DeviceAbi.fromPrimaryAbi("x86_64"))
        assertEquals(null, DeviceAbi.fromPrimaryAbi("mips"))
        // opencode is only distributed for arm64 and x64
        assertEquals(null, DeviceAbi.ARM32.opencodeTarget)
    }
}
