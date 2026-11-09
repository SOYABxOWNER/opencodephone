package io.github.oustery.opencodephone.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.core.linuxenv.*
import io.github.oustery.opencodephone.core.settings.DraftStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

 data class FileEntry(val file: File, val name: String, val isDir: Boolean, val depth: Int)

/** One entry of `git status --porcelain -z` (staged+unstaged combined). */
 data class GitChange(val status: String, val path: String)

 data class FilesUiState(
    val entries: List<FileEntry> = emptyList(), val expanded: Set<String> = emptySet(),
    val workspaceEmpty: Boolean = false, val gitBranch: String? = null, val gitChanges: Int = 0,
    val changeList: List<GitChange> = emptyList(),
    val gitProject: String? = null, val editing: EditingFile? = null,
    val showCloneDialog: Boolean = false, val showNewFileDialog: Boolean = false,
    val showCommitDialog: Boolean = false,
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
    private val drafts = DraftStore(java.io.File(graph.appContext.filesDir, "drafts"))
    private val gitLock = Mutex()
    private val scanEpoch = AtomicLong()
    private val editEpoch = AtomicLong()
    private var refreshJob: Job? = null
    private var editorDraftJob: Job? = null
    /** Path currently mirrored in the editor draft; guards clear-on-close. */
    @Volatile private var editorDraftPath: String? = null
    @Volatile private var project: File? = null

    init {
        refresh()
        restoreEditorDraft()
    }

    /**
     * Restore the unsaved editor buffer after process death (F-003). The
     * snapshot is reopened fresh so the save-time external-change check
     * still protects against agent edits made while the app was dead.
     */
    private fun restoreEditorDraft() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = drafts.readEditor() ?: return@launch
            val file = java.io.File(saved.path)
            if (!file.isFile) {
                drafts.clearEditor()
                return@launch
            }
            try {
                val snapshot = files.open(file)
                if (editEpoch.get() == 0L) {
                    editorDraftPath = saved.path
                    _state.update { it.copy(editing = EditingFile(snapshot, saved.content)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                drafts.clearEditor()
            }
        }
    }

    /** Mirror the editor buffer into the draft store; cleared on save/close. */
    private fun scheduleEditorDraft() {
        val editing = _state.value.editing ?: return
        editorDraftPath = editing.file.absolutePath
        editorDraftJob?.cancel()
        editorDraftJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            drafts.writeEditor(DraftStore.EditorDraft(editing.file.absolutePath, editing.content))
        }
    }

    /** Publish the selected repository as the shared app context (F-001). */
    private fun publishProject(repo: File?) {
        val relative = repo?.let {
            workspace.toPath().toAbsolutePath().normalize()
                .relativize(it.toPath().toAbsolutePath().normalize()).toString().ifEmpty { null }
        }
        graph.selectProject(relative)
    }

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
                    if (project == null) {
                        project = repos.firstOrNull()
                        publishProject(project)
                    }
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
                publishProject(entry.file)
                migrate(entry.file)
                refreshGit()
            }
        }
        refresh()
    }

    fun openFile(entry: FileEntry) {
        val ticket = editEpoch.incrementAndGet()
        editorDraftJob?.cancel()
        task {
            val snapshot = files.open(entry.file)
            if (ticket == editEpoch.get()) _state.update { it.copy(editing = EditingFile(snapshot, snapshot.content)) }
        }
    }
    fun updateEditingContent(value: String) {
        _state.update { it.copy(editing = it.editing?.copy(content = value)) }
        scheduleEditorDraft()
    }
    fun saveEditing() {
        val editing = _state.value.editing ?: return
        editorDraftJob?.cancel()
        task {
            files.save(editing.snapshot, editing.content)
            if (editing.file.absolutePath == editorDraftPath) drafts.clearEditor()
            _state.update { if (it.editing === editing) it.copy(editing = null, message = "Saved ${editing.file.name}") else it }
            gitLock.withLock { refreshGit() }
        }
    }
    fun closeEditor() {
        editEpoch.incrementAndGet()
        editorDraftJob?.cancel()
        // Only drop the mirror of the file being closed; a draft of another
        // file (restored but not yet reopened) must survive (F-003).
        if (_state.value.editing?.file?.absolutePath == editorDraftPath) drafts.clearEditor()
        _state.update { it.copy(editing = null) }
    }
    fun showClone(show: Boolean) = _state.update { it.copy(showCloneDialog = show) }
    fun showNewFile(show: Boolean) = _state.update { it.copy(showNewFileDialog = show) }
    fun showCommit(show: Boolean) = _state.update { it.copy(showCommitDialog = show) }
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
        // -z keeps paths unquoted and NUL-separated, so spaces and exotic
        // characters survive the round-trip (F-008).
        val result = exec(repo, listOf("git", "status", "--porcelain", "-z", "-b"))
        check(result.exitCode == 0) { "Git status failed" }
        val entries = result.output.split('\u0000').filter { it.isNotBlank() }
        val branch = entries.firstOrNull()?.takeIf { it.startsWith("## ") }?.removePrefix("## ")
        val changes = entries
            .drop(if (branch != null) 1 else 0)
            .map { entry ->
                var path = entry.drop(3)
                // Rename entries read "XY old -> new"; staging the new path
                // records the rename.
                if (path.contains(" -> ")) path = path.substringAfter(" -> ")
                GitChange(status = entry.take(2).trim(), path = path)
            }
        _state.update {
            it.copy(
                gitProject = repo.name, gitBranch = branch,
                changeList = changes, gitChanges = changes.size,
            )
        }
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
                publishProject(target)
                refreshGit()
            }
            refresh()
        }
    }
    fun commit(message: String, selectedPaths: Set<String> = emptySet()) {
        if (message.isBlank()) return
        _state.update { it.copy(showCommitDialog = false) }
        task("Committing…") { gitLock.withLock {
            val repo = checkNotNull(project) { "Select a repository directory" }
            migrate(repo)
            // Stage only what the user picked instead of a blind `git add -A`
            // (functional audit F-008).
            require(selectedPaths.isNotEmpty()) { "Select at least one changed file" }
            check(exec(repo, listOf("git", "add", "--") + selectedPaths.toList()).exitCode == 0) { "Git add failed" }
            val identity = graph.settings.gitConfig()
            val args = listOf("-c", "user.name=${identity.userName}", "-c", "user.email=${identity.userEmail}", "commit", "-m", message)
            check(exec(repo, GitAuth.command(*args.toTypedArray())).exitCode == 0) { "Commit failed" }
            refreshGit()
        } }
    }

    /** Delete a workspace file or directory tree (F-007). */
    fun deleteEntry(entry: FileEntry) {
        val absolute = entry.file.absolutePath
        task {
            files.delete(entry.file)
            // Editor buffers and drafts pointing into the deleted subtree
            // must not survive it.
            drafts.readEditor()?.let { d ->
                if (d.path == absolute || d.path.startsWith("$absolute/")) drafts.clearEditor()
            }
            _state.update { st -> st.copy(
                editing = st.editing?.takeIf { e ->
                    val p = e.file.absolutePath
                    p != absolute && !p.startsWith("$absolute/")
                },
                expanded = st.expanded.filterNot { it == absolute || it.startsWith("$absolute/") }.toSet(),
            ) }
            if (project?.let { it.absolutePath == absolute || it.absolutePath.startsWith("$absolute/") } == true) {
                project = null
                publishProject(null)
            }
            refresh()
        }
    }

    /** Rename a workspace file or directory within its parent (F-007). */
    fun renameEntry(entry: FileEntry, newName: String) {
        val oldPath = entry.file.absolutePath
        task {
            val dest = files.rename(entry.file, newName)
            val newPath = dest.absolutePath
            // The old editor snapshot (hash) is tied to the old path.
            drafts.readEditor()?.let { d -> if (d.path == oldPath) drafts.clearEditor() }
            _state.update { st -> st.copy(
                editing = st.editing?.takeIf { it.file.absolutePath != oldPath },
                expanded = st.expanded.map { if (it == oldPath) newPath else it }.toSet(),
            ) }
            if (project?.absolutePath == oldPath) {
                project = dest
                publishProject(dest)
            }
            refresh()
        }
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
