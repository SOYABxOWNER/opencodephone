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

 data class GitChange(val status: String, val path: String)

 data class FilesUiState(
    /** Direct children per loaded directory path (sorted, filtered). */
    val children: Map<String, List<FileEntry>> = emptyMap(),
    /** Flattened visible rows derived from [children] + [expanded]. */
    val rows: List<FileEntry> = emptyList(),
    val expanded: Set<String> = emptySet(),
    /** Directories whose single-level load is still in flight. */
    val loadingDirs: Set<String> = emptySet(),
    /** Per-directory load errors; tapping the directory retries. */
    val dirErrors: Map<String, String> = emptyMap(),
    /** Dotfiles (`.git`, `.env`, …) stay hidden until the user asks. */
    val hiddenVisible: Boolean = false,
    /** False until the first root listing lands (skeleton phase). */
    val workspaceLoaded: Boolean = false,
    /** True once loaded and the visible tree is empty (empty-state CTA). */
    val workspaceEmpty: Boolean = false,
    val gitBranch: String? = null, val gitChanges: Int = 0,
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

    // ------------------------------------------------------------------ tree

    /** Builds rows from the loaded tree; the single source of row truth. */
    private fun rebuild(s: FilesUiState): FilesUiState {
        val rows = FileTree.flatten(s.children, s.expanded, workspace.absolutePath)
        return s.copy(
            rows = rows,
            workspaceEmpty = s.workspaceLoaded && rows.isEmpty(),
        )
    }

    /**
     * Lists one directory level: visible (non-hidden unless requested)
     * entries, directories first, each annotated with size/child count.
     * A directory load failure only marks that directory — the rest of the
     * tree keeps working (the old global scan died on the first bad entry).
     */
    private fun listChildren(dir: File, hiddenVisible: Boolean): List<FileEntry> {
        files.checked(dir)
        val all = dir.listFiles()
            ?: throw IllegalStateException(graph.appContext.getString(R.string.files_folder_unreadable, dir.name))
        val visible = all
            .filter { hiddenVisible || !FileTree.isHiddenName(it.name) }
            .filter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        val entries = visible.map { child ->
            if (child.isDirectory) {
                val count = child.listFiles()
                    ?.count { hiddenVisible || !FileTree.isHiddenName(it.name) } ?: -1
                FileEntry(child, child.name, true, 0, childCount = count)
            } else {
                FileEntry(child, child.name, false, 0, size = child.length())
            }
        }
        return FileTree.sortEntries(entries)
    }

    /** Merges a freshly listed directory into the tree state. */
    private fun withLoadedDir(path: String, list: List<FileEntry>?) {
        _state.update { s ->
            val kids = if (list == null) s.children - path else s.children + (path to list)
            rebuild(
                s.copy(
                    children = kids,
                    loadingDirs = s.loadingDirs - path,
                    dirErrors = if (list == null) s.dirErrors else s.dirErrors - path,
                    workspaceLoaded = if (path == workspace.absolutePath) true else s.workspaceLoaded,
                ),
            )
        }
    }

    /** Loads a single directory level without touching the rest of the tree. */
    private fun loadChildren(path: String) {
        _state.update { it.copy(loadingDirs = it.loadingDirs + path, dirErrors = it.dirErrors - path) }
        viewModelScope.launch(Dispatchers.IO) {
            val hidden = _state.value.hiddenVisible
            val list = try {
                listChildren(File(path), hidden)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s ->
                    if (path in s.loadingDirs) {
                        s.copy(
                            loadingDirs = s.loadingDirs - path,
                            dirErrors = s.dirErrors + (path to (e.message ?: "Load failed")),
                        )
                    } else s
                }
                return@launch
            }
            withLoadedDir(path, list)
        }
    }

    fun toggleHidden() {
        val nowVisible = !_state.value.hiddenVisible
        _state.update { s -> rebuild(s.copy(hiddenVisible = nowVisible)) }
        // Hidden entries change what each loaded directory shows: reload
        // everything already on screen (bounded by what the user expanded).
        val targets = _state.value.children.keys.toList()
        viewModelScope.launch(Dispatchers.IO) {
            for (path in targets) {
                val list = runCatching { listChildren(File(path), nowVisible) }.getOrNull() ?: continue
                withLoadedDir(path, list)
            }
        }
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
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Reload the workspace root plus every loaded directory that
                // is still expanded; expansion state survives the refresh.
                val loaded = _state.value.children.keys
                val targets = (loaded + workspace.absolutePath)
                    .filter { it == workspace.absolutePath || it in _state.value.expanded }
                for (path in targets) {
                    val list = runCatching { listChildren(File(path), _state.value.hiddenVisible) }.getOrNull()
                    if (list != null) {
                        withLoadedDir(path, list)
                    } else if (path == workspace.absolutePath && _state.value.children[path] == null) {
                        // First root listing failed (root is app-private, so
                        // this is rare): finish the skeleton phase instead of
                        // spinning forever, with the empty-state CTA.
                        withLoadedDir(path, emptyList())
                    }
                    // Otherwise keep the previous listing of that directory.
                }
                if (!_state.value.workspaceLoaded) {
                    withLoadedDir(workspace.absolutePath, emptyList())
                }
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

    /**
     * Expand/collapse one directory. The flip is synchronous so the chevron
     * answers immediately; children load for that directory alone, so a slow
     * or broken folder can no longer block or drop the rest of the tree.
     */
    fun toggleDir(entry: FileEntry) {
        if (!entry.isDir) return
        val path = entry.file.absolutePath
        val expanding = path !in _state.value.expanded
        _state.update { s -> rebuild(s.copy(expanded = if (expanding) s.expanded + path else s.expanded - path)) }
        if (expanding) {
            if (path !in _state.value.children) loadChildren(path)
            task {
                if (File(files.checked(entry.file), ".git").exists()) gitLock.withLock {
                    project = entry.file
                    publishProject(entry.file)
                    migrate(entry.file)
                    refreshGit()
                }
            }
        }
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
            _state.update { st ->
                val dropped = { key: String -> key == absolute || key.startsWith("$absolute/") }
                rebuild(
                    st.copy(
                        editing = st.editing?.takeIf { e ->
                            val p = e.file.absolutePath
                            p != absolute && !p.startsWith("$absolute/")
                        },
                        expanded = st.expanded.filterNot(dropped).toSet(),
                        children = st.children.filterKeys { !dropped(it) },
                    ),
                )
            }
            if (project?.let { it.absolutePath == absolute || it.absolutePath.startsWith("$absolute/") } == true) {
                project = null
                publishProject(null)
            }
            refresh()
        }
    }

    /** Rename a workspace file or directory within its parent (F-007). */
    fun renameEntry(entry: FileEntry, newName: String) {
        task {
            val dest = files.rename(entry.file, newName)
            val oldPath = entry.file.absolutePath
            // The old editor snapshot (hash) is tied to the old path.
            drafts.readEditor()?.let { d -> if (d.path == oldPath) drafts.clearEditor() }
            _state.update { st ->
                // Cached listings under the old path prefix hold stale File
                // objects: drop the subtree (the trailing refresh reloads the
                // affected parents) and let the user expand the new name.
                val dropped = { key: String -> key == oldPath || key.startsWith("$oldPath/") }
                rebuild(
                    st.copy(
                        editing = st.editing?.takeIf { it.file.absolutePath != oldPath },
                        expanded = st.expanded.filterNot(dropped).toSet(),
                        children = st.children.filterKeys { !dropped(it) },
                    ),
                )
            }
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

    /**
     * Fast-forward pull from origin (functional audit F-015): fetches through
     * the same HTTPS URL + credential helper as push and refuses to merge
     * diverged history (`--ff-only`), so local work is never silently mixed
     * with incoming commits.
     */
    fun pull() {
        task("Pulling…") {
            gitLock.withLock {
                val repo = checkNotNull(project) { "Select a repository directory" }
                migrate(repo)
                val branch = exec(repo, listOf("git", "rev-parse", "--abbrev-ref", "HEAD")).output.trim()
                require(branch.isNotEmpty() && branch != "HEAD") { "No current branch" }
                val remote = exec(repo, listOf("git", "remote", "get-url", "origin"))
                check(remote.exitCode == 0) { "No remote" }
                val clean = GitAuth.cleanUrl(remote.output.trim())
                val result = exec(
                    repo,
                    GitAuth.command("pull", "--ff-only", clean, branch),
                    GitAuth.environment(clean, graph.settings.gitConfig().githubToken),
                )
                check(result.exitCode == 0) { "Pull failed (exit ${result.exitCode})" }
                _state.update { it.copy(message = "Pulled from origin") }
            }
            refresh()
        }
    }
    fun dismissMessage() = _state.update { it.copy(message = null) }
}
