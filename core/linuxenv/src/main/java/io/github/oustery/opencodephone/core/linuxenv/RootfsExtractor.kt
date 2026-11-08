package io.github.oustery.opencodephone.core.linuxenv

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Guest absolute links are converted to rootfs-relative links, never host absolute paths. */
object RootfsExtractor {
    fun extract(tarGz: File, destDir: File, replace: Boolean = true, checkCancellation: () -> Unit = {}) {
        check(tarGz.isFile)
        if (replace && destDir.exists()) check(destDir.deleteRecursively())
        check(destDir.mkdirs() || destDir.isDirectory)
        val root = destDir.canonicalFile.toPath()
        val hardlinks = mutableListOf<Pair<Path, Path>>()
        fun within(path: Path): Path {
            val normalized = path.normalize()
            check(normalized.startsWith(root)) { "Archive path escapes rootfs" }
            check(normalized.toFile().canonicalFile.toPath().startsWith(root)) { "Archive link escapes rootfs" }
            return normalized
        }
        GZIPInputStream(tarGz.inputStream().buffered(), 128 * 1024).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    checkCancellation()
                    val entry = tar.nextTarEntry ?: break
                    val target = root.resolve(entry.name.trimStart('/')).normalize()
                    check(target.startsWith(root)) { "Archive entry escapes rootfs" }
                    val parent = target.parent
                    if (target != root) {
                        within(parent)
                        Files.createDirectories(parent)
                    }
                    when {
                        entry.isSymbolicLink -> {
                            val physicalParent = parent.toFile().canonicalFile.toPath()
                            val desired = if (entry.linkName.startsWith('/')) root.resolve(entry.linkName.trimStart('/'))
                                else physicalParent.resolve(entry.linkName)
                            within(desired)
                            Files.deleteIfExists(target)
                            val relative = physicalParent.relativize(desired.normalize())
                            Files.createSymbolicLink(target, if (relative.toString().isEmpty()) java.nio.file.Paths.get(".") else relative)
                        }
                        entry.isLink -> {
                            within(target)
                            hardlinks += target to within(root.resolve(entry.linkName.trimStart('/')))
                        }
                        entry.isDirectory -> Files.createDirectories(within(target))
                        entry.isFile -> {
                            within(target)
                            Files.newOutputStream(target).use { out ->
                                val bytes = ByteArray(64 * 1024)
                                while (true) {
                                    checkCancellation()
                                    val n = tar.read(bytes)
                                    if (n < 0) break
                                    out.write(bytes, 0, n)
                                }
                            }
                            if (entry.mode and 64 != 0) check(target.toFile().setExecutable(true, false))
                        }
                        // Device nodes and other special archive entries are not materialized app-side.
                    }
                }
            }
        }
        while (hardlinks.isNotEmpty()) {
            checkCancellation()
            val ready = hardlinks.filter { within(it.second).toFile().isFile }
            check(ready.isNotEmpty()) { "Unresolved archive hardlink" }
            ready.forEach { (target, source) ->
                within(source).toFile().copyTo(within(target).toFile(), overwrite = true)
            }
            hardlinks.removeAll(ready.toSet())
        }
    }
}
