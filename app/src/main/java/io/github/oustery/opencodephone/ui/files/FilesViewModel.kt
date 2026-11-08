package io.github.oustery.opencodephone.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.core.linuxenv.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

 data class FileEntry(val file: File, val name: String, val isDir: Boolean, val depth: Int)
 data class FilesUiState(
    val entries: List<FileEntry> = emptyList(), val expanded: Set<String> = emptySet(),
    val workspaceEmpty: Boolean = false, val gitBranch: String? = null, val gitChanges: Int = 0,
    val gitProject: String? = null, val editing: EditingFile? = null,
    val showCloneDialog: Boolean = false, val showNewFileDialog: Boolean = false,
    val busy: String? = null, val message: String? = null,
)
 data class EditingFile(val snapshot: WorkspaceFiles.Snapshot, val content: String) {
    val file: File get() = snapshot.file
}

class FilesViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state
    private val workspace = graph.layout.workspaceDir
    private val files = WorkspaceFiles(workspace)
    private val gitLock = Mutex()
    private val scanEpoch = AtomicLong()
    private val editEpoch = AtomicLong()
    private var refreshJob: Job? = null
    @Volatile private var project: File? = null

    init { refresh() }

    private fun task(label: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (label != null) _state.update { it.copy(busy = label) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = e.message ?: "Operation failed") } }
            finally { if (label != null) _state.update { it.copy(busy = null) } }
        }
    }

    fun refresh() {
        val ticket = scanEpoch.incrementAndGet()
        val expanded = _state.value.expanded
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = mutableListOf<FileEntry>()
                fun scan(dir: File, depth: Int) {
                    ensureActive()
                    if (depth > 64) return
                    val children = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name }) ?: return
                    for (child in children) {
                        ensureActive()
                        if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                        files.checked(child)
                        entries += FileEntry(child, child.name, child.isDirectory, depth)
                        if (child.isDirectory && child.absolutePath in expanded) scan(child, depth + 1)
                    }
                }
                scan(workspace, 0)
                if (scanEpoch.get() == ticket) _state.update { it.copy(entries = entries, workspaceEmpty = entries.isEmpty()) }
                if (graph.layout.isInstalled()) gitLock.withLock {
                    val repos = (listOf(workspace) + (workspace.listFiles()?.toList() ?: emptyList()))
                        .filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) && File(it, ".git").isDirectory }
                    for (repo in repos) migrate(repo)
                    if (project == null) project = repos.firstOrNull()
                    refreshGit()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = "Refresh failed: ${e.javaClass.simpleName}") } }
        }
    }

    fun toggleDir(entry: FileEntry) {
        _state.update { it.copy(expanded = if (entry.file.absolutePath in it.expanded) it.expanded - entry.file.absolutePath else it.expanded + entry.file.absolutePath) }
        task {
            if (File(files.checked(entry.file), ".git").exists()) gitLock.withLock {
                project = entry.file
                migrate(entry.file)
                refreshGit()
            }
        }
        refresh()
    }

    fun openFile(entry: FileEntry) {
        val ticket = editEpoch.incrementAndGet()
        task {
            val snapshot = files.open(entry.file)
            if (ticket == editEpoch.get()) _state.update { it.copy(editing = EditingFile(snapshot, snapshot.content)) }
        }
    }
    fun updateEditingContent(value: String) = _state.update { it.copy(editing = it.editing?.copy(content = value)) }
    fun saveEditing() {
        val editing = _state.value.editing ?: return
        task {
            files.save(editing.snapshot, editing.content)
            _state.update { if (it.editing === editing) it.copy(editing = null, message = "Saved ${editing.file.name}") else it }
            gitLock.withLock { refreshGit() }
        }
    }
    fun closeEditor() { editEpoch.incrementAndGet(); _state.update { it.copy(editing = null) } }
    fun showClone(show: Boolean) = _state.update { it.copy(showCloneDialog = show) }
    fun showNewFile(show: Boolean) = _state.update { it.copy(showNewFileDialog = show) }
    fun createFile(name: String) { _state.update { it.copy(showNewFileDialog = false) }; task { files.create(name); refresh() } }

    private fun cwd(repo: File): String {
        val relative = workspace.toPath().toAbsolutePath().normalize().relativize(files.checked(repo).toPath())
        return PRootCommand.WORKSPACE_MOUNT + if (relative.toString().isEmpty()) "" else "/$relative"
    }
    private suspend fun exec(repo: File, args: List<String>, env: Map<String, String> = emptyMap()): ExecResult {
        check(graph.layout.isInstalled()) { "Environment is not ready" }
        return graph.runtime.exec(args, env = env, cwd = cwd(repo))
    }
    private suspend fun migrate(repo: File) = GitAuth.migrateRemotes { exec(repo, it) }
    private suspend fun refreshGit() {
        val repo = project ?: return
        val result = exec(repo, listOf("git", "status", "--porcelain", "-b"))
        check(result.exitCode == 0) { "Git status failed" }
        val lines = result.output.lines()
        _state.update { it.copy(gitProject = repo.name, gitBranch = lines.firstOrNull()?.removePrefix("## "), gitChanges = lines.drop(1).count { line -> line.isNotBlank() }) }
    }

    fun cloneRepository(url: String) {
        _state.update { it.copy(showCloneDialog = false) }
        task("Cloning…") {
            gitLock.withLock {
                val clean = GitAuth.cleanUrl(url)
                val name = java.net.URI(clean).path.removeSuffix("/").substringAfterLast('/').removeSuffix(".git")
                require(name.matches(Regex("[A-Za-z0-9._-]+")) && name !in listOf(".", "..", ".git")) { "Invalid repository name" }
                val target = files.checked(File(workspace, name))
                check(!target.exists()) { "Directory already exists" }
                val result = exec(workspace, GitAuth.command("clone", "--", clean, name), GitAuth.environment(clean, graph.settings.gitConfig().githubToken))
                check(result.exitCode == 0) { "Clone failed (exit ${result.exitCode})" }
                project = target
                refreshGit()
            }
            refresh()
        }
    }
    fun commit(message: String) {
        if (message.isBlank()) return
        task("Committing…") { gitLock.withLock {
            val repo = checkNotNull(project) { "Select a repository directory" }
            migrate(repo)
            check(exec(repo, listOf("git", "add", "-A")).exitCode == 0) { "Git add failed" }
            val identity = graph.settings.gitConfig()
            val args = listOf("-c", "user.name=${identity.userName}", "-c", "user.email=${identity.userEmail}", "commit", "-m", message)
            check(exec(repo, GitAuth.command(*args.toTypedArray())).exitCode == 0) { "Commit failed" }
            refreshGit()
        } }
    }
    fun push() {
        task("Pushing…") { gitLock.withLock {
            val repo = checkNotNull(project) { "Select a repository directory" }
            migrate(repo)
            val remote = exec(repo, listOf("git", "remote", "get-url", "--push", "origin"))
            check(remote.exitCode == 0) { "No push remote" }
            val clean = GitAuth.cleanUrl(remote.output.trim())
            val result = exec(repo, GitAuth.command("push", clean, "HEAD"), GitAuth.environment(clean, graph.settings.gitConfig().githubToken))
            check(result.exitCode == 0) { "Push failed (exit ${result.exitCode})" }
            _state.update { it.copy(message = "Pushed") }
        } }
    }
    fun dismissMessage() = _state.update { it.copy(message = null) }
}
