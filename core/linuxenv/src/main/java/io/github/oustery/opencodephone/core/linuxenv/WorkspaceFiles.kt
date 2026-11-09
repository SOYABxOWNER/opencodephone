package io.github.oustery.opencodephone.core.linuxenv

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

/** No symlink navigation. Agent processes sharing the UID are trusted writers, not a sandbox. */
class WorkspaceFiles(workspace: File) {
    private val root = workspace.toPath().toAbsolutePath().normalize()
    data class Snapshot(val file: File, val content: String, val hash: String)

    fun checked(file: File): File {
        val path = file.toPath().toAbsolutePath().normalize()
        require(path.startsWith(root)) { "Path must stay inside workspace" }
        require(!Files.isSymbolicLink(root)) { "Workspace cannot be a symlink" }
        var part = root
        for (name in root.relativize(path)) {
            part = part.resolve(name)
            require(!Files.isSymbolicLink(part)) { "Symlinks are not editable" }
        }
        return path.toFile()
    }

    fun create(name: String): File {
        require(name.isNotBlank())
        val file = checked(root.resolve(name.trim()).toFile())
        require(file.toPath() != root)
        if (name.trim().endsWith('/')) Files.createDirectory(file.toPath()) else {
            Files.createDirectories(file.parentFile.toPath())
            Files.newByteChannel(checked(file).toPath(), setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)).use { }
        }
        return file
    }

    /** Delete a file or a whole directory tree inside the workspace (F-007). */
    fun delete(file: File) {
        val target = checked(file)
        require(target.toPath() != root) { "Workspace root cannot be deleted" }
        target.deleteRecursively()
    }

    /** Rename a file or directory inside its parent directory (F-007). */
    fun rename(file: File, newName: String): File {
        val name = newName.trim()
        require(name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')) {
            "Invalid name"
        }
        val target = checked(file)
        require(target.toPath() != root) { "Workspace root cannot be renamed" }
        val dest = checked(File(target.parentFile, name))
        require(!dest.exists()) { "Name already in use" }
        try {
            Files.move(target.toPath(), dest.toPath(), ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(target.toPath(), dest.toPath())
        }
        return dest
    }

    private fun bytes(file: File): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        Files.newByteChannel(checked(file).toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocate(8192)
            while (true) {
                val n = channel.read(buffer)
                if (n < 0) break
                require(out.size() + n <= 2 * 1024 * 1024) { "File is too large to edit" }
                out.write(buffer.array(), 0, n)
                buffer.clear()
            }
        }
        return out.toByteArray()
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun open(file: File): Snapshot {
        val data = bytes(file)
        require(data.none { it == 0.toByte() }) { "Binary file is not editable" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        return Snapshot(checked(file), text, hash(data))
    }

    fun save(snapshot: Snapshot, content: String) {
        val file = checked(snapshot.file)
        check(hash(bytes(file)) == snapshot.hash) { "File changed externally; reopen before saving" }
        val temp = Files.createTempFile(file.parentFile.toPath(), ".edit-", ".tmp").toFile()
        try {
            java.io.FileOutputStream(temp).use { it.write(content.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            if (file.canExecute()) check(temp.setExecutable(true, true))
            checked(file)
            check(hash(bytes(file)) == snapshot.hash) { "File changed externally; reopen before saving" }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}
