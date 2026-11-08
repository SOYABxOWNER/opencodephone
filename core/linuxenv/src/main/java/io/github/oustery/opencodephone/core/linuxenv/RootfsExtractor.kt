package io.github.oustery.opencodephone.core.linuxenv

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * Extracts the Debian rootfs tarball with permission and symlink handling.
 */
object RootfsExtractor {

    /**
     * Extract a tar.gz rootfs archive into [destDir]. When [replace] is true
     * any previous content is removed first; when false the archive is
     * merged into the existing tree (used for the toolset overlay).
     * Symlinks are recreated (with a copy fallback); executable bits
     * from the tar mode are preserved.
     */
    fun extract(tarGz: File, destDir: File, replace: Boolean = true) {
        check(tarGz.exists()) { "Archive not found: $tarGz" }
        if (replace && destDir.exists()) {
            destDir.deleteRecursively()
        }
        destDir.mkdirs()
        val destRoot = destDir.canonicalPath

        TarArchiveInputStream(
            GZIPInputStream(BufferedInputStream(FileInputStream(tarGz)), 128 * 1024),
        ).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                val target = File(destDir, entry.name)
                // Path traversal guard: canonical target must stay inside destDir.
                if (!target.canonicalPath.startsWith(destRoot)) continue

                when {
                    entry.isDirectory -> target.mkdirs()

                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                            // Overlay mode: a later package may legitimately
                            // replace an earlier symlink or file.
                            target.delete()
                        }
                        createSymlink(target, entry.linkName, destDir)
                    }

                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = resolveWithin(destDir, entry.linkName)
                        if (linkTarget != null && linkTarget.exists()) {
                            linkTarget.copyTo(target, overwrite = true)
                        }
                    }

                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { tar.copyTo(it) }
                        applyMode(target, entry.mode)
                    }
                }
            }
        }
    }

    private fun createSymlink(target: File, linkName: String, destDir: File) {
        try {
            Files.deleteIfExists(target.toPath())
            val linkPath: Path = target.toPath()
            val targetPath: Path = resolveWithin(destDir, linkName)?.toPath() ?: Path.of(linkName)
            Files.createSymbolicLink(linkPath, targetPath)
        } catch (_: Exception) {
            // Fallback: materialize the link target as a copy when symlinks are
            // unavailable on this filesystem.
            resolveWithin(destDir, linkName)?.let { src ->
                if (src.isDirectory) {
                    target.mkdirs()
                } else if (src.exists()) {
                    src.copyTo(target, overwrite = true)
                }
            }
        }
    }

    /** Resolve a (possibly relative) link target within the rootfs. */
    private fun resolveWithin(destDir: File, linkName: String): File? {
        val target = if (linkName.startsWith("/")) {
            File(destDir, linkName)
        } else {
            null
        } ?: File(destDir, linkName)
        return if (target.exists()) target.canonicalFile else null
    }

    private fun applyMode(file: File, mode: Int) {
        // user execute bit (0o100) — also covers directories created as files
        if (mode and 0b001_000_000 != 0) {
            file.setExecutable(true, false)
        }
    }
}
