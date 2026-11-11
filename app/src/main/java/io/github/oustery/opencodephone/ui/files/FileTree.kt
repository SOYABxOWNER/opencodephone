package io.github.oustery.opencodephone.ui.files

import java.io.File
import java.util.Locale

/**
 * One row of the file explorer tree. [depth] is the visual nesting level of
 * the flattened view; [size] is the file length in bytes for files (-1 for
 * directories); [childCount] is the number of visible entries a directory
 * carries once it has been loaded (-1 while unknown).
 */
data class FileEntry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val depth: Int,
    val size: Long = -1L,
    val childCount: Int = -1,
)

/**
 * Pure helpers of the lazy file tree (no Android imports, JVM-testable).
 *
 * The explorer loads one directory level at a time: [flatten] turns the
 * loaded-children map plus the expanded set into the visible row list.
 * Directories that are expanded but not loaded yet simply show no children
 * (the row itself signals the pending load), and expanding a directory never
 * rescans the rest of the tree — that global rescan was the old expansion bug.
 */
object FileTree {

    /**
     * Depth-first flattening. [children] maps a directory path to its direct
     * visible children (already sorted); [root] is the workspace itself.
     * The returned entries carry their visual [FileEntry.depth].
     */
    fun flatten(
        children: Map<String, List<FileEntry>>,
        expanded: Set<String>,
        root: String,
    ): List<FileEntry> {
        val rows = mutableListOf<FileEntry>()
        fun emit(path: String, depth: Int) {
            val kids = children[path] ?: return
            for (child in kids) {
                val positioned = child.copy(depth = depth)
                rows += positioned
                if (positioned.isDir && positioned.file.path in expanded) {
                    emit(positioned.file.path, depth + 1)
                }
            }
        }
        emit(root, 0)
        return rows
    }

    /** Sort: directories first, then case-insensitive by name. */
    fun sortEntries(entries: List<FileEntry>): List<FileEntry> =
        entries.sortedWith(
            compareByDescending<FileEntry> { it.isDir }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.name },
        )

    /** Human-readable file size (B / KB / MB / GB), for the row tail. */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (value >= 100) "%d %s".format(Locale.ROOT, value.toLong(), units[unit])
        else "%.1f %s".format(Locale.ROOT, value, units[unit])
    }

    /** True when a name is a dotfile (hidden by default in the explorer). */
    fun isHiddenName(name: String): Boolean = name.startsWith(".") && name != "." && name != ".."
}
