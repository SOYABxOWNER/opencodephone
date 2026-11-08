package io.github.oustery.opencodephone.core.linuxenv

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class RootfsExtractorTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    /** Build a tar.gz with the given (path, content or dir) entries. */
    private fun tarGz(dest: File, entries: Map<String, String?>) {
        val raw = File.createTempFile("tar", ".tar")
        TarArchiveOutputStream(FileOutputStream(raw)).use { tar ->
            entries.forEach { (path, content) ->
                if (content == null) {
                    val e = TarArchiveEntry("$path/")
                    tar.putArchiveEntry(e)
                    tar.closeArchiveEntry()
                } else {
                    val e = TarArchiveEntry(path)
                    e.mode = 0b111_101_101
                    e.size = content.toByteArray().size.toLong()
                    tar.putArchiveEntry(e)
                    tar.write(content.toByteArray())
                    tar.closeArchiveEntry()
                }
            }
        }
        GZIPOutputStream(FileOutputStream(dest)).use { gz -> raw.inputStream().copyTo(gz) }
        raw.delete()
    }

    @Test
    fun `traversal and sibling prefix entries are rejected`() {
        val parent = tempDir("extract-boundary")
        val dest = File(parent, "root").apply { mkdirs() }
        val archive = File(parent, "bad.tar.gz")
        tarGz(archive, mapOf("../root-other/escape" to "bad"))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            RootfsExtractor.extract(archive, dest)
        }
        assertFalse(File(parent, "root-other/escape").exists())
    }

    @Test
    fun `relative dot absolute forward links resolve independently of archive order`() {
        val dest = tempDir("extract-links")
        val archive = File(tempDir("extract-links-src"), "links.tar.gz")
        GZIPOutputStream(FileOutputStream(archive)).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                fun link(path: String, target: String, type: Byte) {
                    val entry = TarArchiveEntry(path, type)
                    entry.linkName = target
                    tar.putArchiveEntry(entry); tar.closeArchiveEntry()
                }
                link("usr/lib/dot", ".", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK)
                link("usr/lib/absolute", "/usr/bin/tool", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK)
                link("hard", "usr/bin/tool", org.apache.commons.compress.archivers.tar.TarConstants.LF_LINK)
                val entry = TarArchiveEntry("usr/bin/tool")
                entry.size = 4
                tar.putArchiveEntry(entry); tar.write("data".toByteArray()); tar.closeArchiveEntry()
            }
        }
        RootfsExtractor.extract(archive, dest)
        assertEquals(File(dest, "usr/lib").canonicalFile, File(dest, "usr/lib/dot").canonicalFile)
        assertEquals("data", File(dest, "usr/lib/absolute").readText())
        assertEquals("data", File(dest, "hard").readText())
        dest.deleteRecursively()
    }

    @Test
    fun `replace mode removes previous content`() {
        val dest = tempDir("extract-replace")
        File(dest, "stale").mkdirs()
        File(dest, "stale/old.txt").writeText("old")
        val tar = tempDir("extract-src")
        val archive = File(tar, "root.tar.gz")
        tarGz(archive, mapOf("new.txt" to "fresh"))

        RootfsExtractor.extract(archive, dest)
        assertFalse(File(dest, "stale").exists())
        assertEquals("fresh", File(dest, "new.txt").readText())
    }

    @Test
    fun `merge mode keeps existing files and overwrites overlaps`() {
        val dest = tempDir("extract-merge")
        File(dest, "etc").mkdirs()
        File(dest, "etc/config").writeText("keep")
        File(dest, "usr/bin").mkdirs()
        File(dest, "usr/bin/tool").writeText("v1")

        val tar = tempDir("extract-src2")
        val archive = File(tar, "toolset.tar.gz")
        tarGz(archive, mapOf("usr/bin/tool" to "v2"))

        RootfsExtractor.extract(archive, dest, replace = false)
        assertEquals("keep", File(dest, "etc/config").readText())
        assertEquals("v2", File(dest, "usr/bin/tool").readText())
    }

    @Test
    fun `symlink replaces file in merge mode and preserves exec bits`() {
        val dest = tempDir("extract-symlink")
        val archive = File(tempDir("extract-src3"), "a.tar.gz")

        // First pass: usr/bin/sh is a real file.
        val tar1 = tempDir("t1")
        val a1 = File(tar1, "one.tar.gz")
        tarGz(a1, mapOf("usr/bin/sh" to "#!/bin/sh\n"))
        RootfsExtractor.extract(a1, dest, replace = false)
        assertTrue(File(dest, "usr/bin/sh").isFile)

        // Second pass: the same path is now a symlink to dash; merge must
        // replace the file instead of failing.
        val raw = File.createTempFile("tar2", ".tar")
        TarArchiveOutputStream(FileOutputStream(raw)).use { tar ->
            val dash = TarArchiveEntry("usr/bin/dash")
            val body = "dash-binary".toByteArray()
            dash.mode = 0b111_101_101
            dash.size = body.size.toLong()
            tar.putArchiveEntry(dash)
            tar.write(body)
            tar.closeArchiveEntry()
            val link = TarArchiveEntry("usr/bin/sh", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK)
            link.linkName = "dash"
            tar.putArchiveEntry(link)
            tar.closeArchiveEntry()
        }
        GZIPOutputStream(FileOutputStream(archive)).use { gz -> raw.inputStream().copyTo(gz) }
        RootfsExtractor.extract(archive, dest, replace = false)

        assertTrue(Files.isSymbolicLink(File(dest, "usr/bin/sh").toPath()))
        assertTrue(File(dest, "usr/bin/dash").isFile)
        assertTrue(File(dest, "usr/bin/dash").canExecute())
    }

    @Test
    fun `dpkg status merge replaces same package and appends new ones`() {
        val rootfs = tempDir("dpkg-root")
        val dpkg = File(rootfs, "var/lib/dpkg").apply { mkdirs() }
        File(dpkg, "status").writeText(
            """
            Package: libc6
            Version: 2.41-1
            Status: install ok installed

            Package: base-files
            Version: 13.7
            Status: install ok installed
            """.trimIndent() + "\n",
        )
        File(dpkg, "status.d").apply { mkdirs() }.apply {
            File(this, "libc6").writeText(
                """
                Package: libc6
                Version: 2.41-12
                Architecture: arm64
                Status: install ok installed
                """.trimIndent() + "\n",
            )
            File(this, "git").writeText(
                """
                Package: git
                Version: 1:2.47.3
                Architecture: arm64
                Status: install ok installed
                """.trimIndent() + "\n",
            )
        }

        ToolsetInstaller(DockerRegistryClient.longTimeoutClient()).mergeDpkgStatus(rootfs)

        val merged = File(dpkg, "status").readText()
        assertTrue(merged.contains("Version: 2.41-12"))
        assertFalse(merged.contains("Version: 2.41-1\n"))
        assertTrue(merged.contains("Package: git"))
        assertTrue(merged.contains("Package: base-files"))
        // Fragments are consumed after the merge.
        assertFalse(File(dpkg, "status.d").exists())
    }
}
