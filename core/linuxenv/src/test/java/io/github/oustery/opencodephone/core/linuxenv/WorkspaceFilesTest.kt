package io.github.oustery.opencodephone.core.linuxenv

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WorkspaceFilesTest {
    @Test fun `rejects traversal symlinks and overwrite on create`() {
        val base = Files.createTempDirectory("workspace-test").toFile()
        try {
            val root = java.io.File(base, "root").apply { mkdirs() }
            val fs = WorkspaceFiles(root)
            assertThrows(IllegalArgumentException::class.java) { fs.create("../escape") }
            fs.create("text")
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { fs.create("text") }
            Files.createSymbolicLink(java.io.File(root, "outside").toPath(), base.toPath())
            assertThrows(IllegalArgumentException::class.java) { fs.create("outside/escape") }
        } finally { base.deleteRecursively() }
    }
    @Test fun `binary invalid utf8 and conflicting save preserve original`() {
        val root = Files.createTempDirectory("workspace-save").toFile()
        try {
            val fs = WorkspaceFiles(root)
            val file = fs.create("text")
            file.writeBytes(byteArrayOf(0, 1))
            assertThrows(IllegalArgumentException::class.java) { fs.open(file) }
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertThrows(java.nio.charset.CharacterCodingException::class.java) { fs.open(file) }
            file.writeText("initial")
            val snapshot = fs.open(file)
            file.writeText("external")
            assertThrows(IllegalStateException::class.java) { fs.save(snapshot, "editor") }
            assertEquals("external", file.readText())
            fs.save(fs.open(file), "saved")
            assertEquals("saved", file.readText())
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally { root.deleteRecursively() }
    }
}
