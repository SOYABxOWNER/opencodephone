package io.github.oustery.opencodephone.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

class FileTreeTest {

    private fun dir(path: String) = FileEntry(java.io.File(path), path.substringAfterLast('/'), true, 0)
    private fun file(path: String, size: Long = -1) =
        FileEntry(java.io.File(path), path.substringAfterLast('/'), false, 0, size)

    @Test
    fun `collapsed tree shows only the root children`() {
        val children = mapOf(
            "/ws" to listOf(dir("/ws/repo"), file("/ws/README.md")),
        )
        val rows = FileTree.flatten(children, expanded = emptySet(), root = "/ws")
        assertEquals(listOf("/ws/repo", "/ws/README.md"), rows.map { it.file.path })
        assertEquals(listOf(0, 0), rows.map { it.depth })
    }

    @Test
    fun `expanded directories emit nested rows with depth`() {
        val children = mapOf(
            "/ws" to listOf(dir("/ws/repo"), file("/ws/README.md")),
            "/ws/repo" to listOf(dir("/ws/repo/src"), file("/ws/repo/app.kt")),
            "/ws/repo/src" to listOf(file("/ws/repo/src/Main.kt")),
        )
        val rows = FileTree.flatten(children, expanded = setOf("/ws/repo", "/ws/repo/src"), root = "/ws")
        assertEquals(
            listOf("/ws/repo", "/ws/repo/src", "/ws/repo/src/Main.kt", "/ws/repo/app.kt", "/ws/README.md"),
            rows.map { it.file.path },
        )
        assertEquals(listOf(0, 1, 2, 1, 0), rows.map { it.depth })
    }

    @Test
    fun `expanded but not loaded directory shows no children yet`() {
        val children = mapOf(
            "/ws" to listOf(dir("/ws/empty-looking")),
        )
        val rows = FileTree.flatten(children, expanded = setOf("/ws/empty-looking"), root = "/ws")
        assertEquals(listOf("/ws/empty-looking"), rows.map { it.file.path })
        assertEquals(0, rows[0].depth)
    }

    @Test
    fun `collapse hides the whole subtree`() {
        val children = mapOf(
            "/ws" to listOf(dir("/ws/a"), dir("/ws/b")),
            "/ws/a" to listOf(file("/ws/a/inner.txt")),
        )
        val rows = FileTree.flatten(children, expanded = setOf("/ws/a"), root = "/ws")
        assertEquals(listOf("/ws/a", "/ws/a/inner.txt", "/ws/b"), rows.map { it.file.path })
        val collapsed = FileTree.flatten(children, expanded = emptySet(), root = "/ws")
        assertEquals(listOf("/ws/a", "/ws/b"), collapsed.map { it.file.path })
    }

    @Test
    fun `sorting is directories first then case-insensitive names`() {
        val entries = listOf(
            file("/ws/zebra.txt"),
            dir("/ws/Alpha"),
            file("/ws/apple.txt"),
            dir("/ws/beta"),
        )
        val sorted = FileTree.sortEntries(entries)
        assertEquals(
            listOf("Alpha", "beta", "apple.txt", "zebra.txt"),
            sorted.map { it.name },
        )
    }

    @Test
    fun `sizes format for humans`() {
        assertEquals("", FileTree.formatSize(-1))
        assertEquals("512 B", FileTree.formatSize(512))
        assertEquals("1.0 KB", FileTree.formatSize(1024))
        assertEquals("1.5 KB", FileTree.formatSize(1536))
        assertEquals("100 KB", FileTree.formatSize(1024 * 100))
        assertEquals("1.0 MB", FileTree.formatSize(1024 * 1024))
    }

    @Test
    fun `dotfiles are hidden but not the navigation aliases`() {
        assertEquals(true, FileTree.isHiddenName(".git"))
        assertEquals(true, FileTree.isHiddenName(".env"))
        assertEquals(false, FileTree.isHiddenName("git"))
        assertEquals(false, FileTree.isHiddenName("."))
        assertEquals(false, FileTree.isHiddenName(".."))
    }
}
