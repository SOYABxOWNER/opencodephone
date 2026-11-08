package io.github.oustery.opencodephone.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.core.linuxenv.PRootCommand
import io.github.oustery.opencodephone.ui.common.shellEscape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class FileEntry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val depth: Int,
)

data class FilesUiState(
    val entries: List<FileEntry> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val workspaceEmpty: Boolean = false,
    val gitBranch: String? = null,
    val gitChanges: Int = 0,
    val editing: EditingFile? = null,
    val showCloneDialog: Boolean = false,
    val showNewFileDialog: Boolean = false,
    val busy: String? = null,
    val message: String? = null,
)

data class EditingFile(
    val file: File,
    val content: String,
)

/**
 * Works directly on the shared workspace directory: the app and the Debian
 * environment see the same files, so file browsing needs no server round-trip
 * while git operations run inside the environment.
 */
class FilesViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state

    private val workspace: File = graph.layout.workspaceDir

    init {
        refresh()
        refreshGit()
    }

    /** Git runs inside the Debian env; without it the commands only spew proot warnings. */
    private fun envReady(): Boolean {
        val ready = graph.layout.isInstalled()
        if (!ready) {
            _state.update {
                it.copy(
                    busy = null,
                    message = graph.appContext.getString(io.github.oustery.opencodephone.R.string.files_env_not_ready),
                )
            }
        }
        return ready
    }

    fun refresh() {
        val entries = mutableListOf<FileEntry>()
        val expanded = _state.value.expanded
        scan(File(workspace.absolutePath), 0, expanded, entries)
        _state.update {
            it.copy(entries = entries, workspaceEmpty = entries.isEmpty())
        }
    }

    private fun scan(dir: File, depth: Int, expanded: Set<String>, out: MutableList<FileEntry>) {
        val children = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
        ) ?: return
        children.forEach { child ->
            out += FileEntry(child, child.name, child.isDirectory, depth)
            if (child.isDirectory && child.absolutePath in expanded) {
                scan(child, depth + 1, expanded, out)
            }
        }
    }

    fun toggleDir(entry: FileEntry) {
        if (!entry.isDir) return
        _state.update {
            val expanded = it.expanded.toMutableSet()
            if (entry.file.absolutePath in expanded) expanded.remove(entry.file.absolutePath)
            else expanded.add(entry.file.absolutePath)
            it.copy(expanded = expanded)
        }
        refresh()
    }

    fun openFile(entry: FileEntry) {
        val file = entry.file
        if (file.isDirectory || file.length() > 2L * 1024 * 1024) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val content = runCatching { file.readText() }.getOrNull()
            _state.update {
                if (content != null) {
                    it.copy(editing = EditingFile(file, content))
                } else {
                    it.copy(message = "Binary or unreadable file: ${file.name}")
                }
            }
        }
    }

    fun updateEditingContent(value: String) {
        _state.update {
            it.copy(editing = it.editing?.copy(content = value))
        }
    }

    fun saveEditing() {
        val editing = _state.value.editing ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { editing.file.writeText(editing.content) }
                .onSuccess {
                    _state.update { it.copy(editing = null, message = "Saved ${editing.file.name}") }
                }
                .onFailure { e ->
                    _state.update { it.copy(message = e.message) }
                }
            refreshGit()
        }
    }

    fun closeEditor() = _state.update { it.copy(editing = null) }

    fun showClone(show: Boolean) = _state.update { it.copy(showCloneDialog = show) }

    fun showNewFile(show: Boolean) = _state.update { it.copy(showNewFileDialog = show) }

    fun cloneRepository(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        if (!envReady()) return
        val name = trimmed.removeSuffix(".git").substringAfterLast('/').ifBlank { "repository" }
        _state.update { it.copy(showCloneDialog = false, busy = "Cloning $name…") }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val target = File(workspace, name)
            if (target.exists()) {
                _state.update { it.copy(busy = null, message = "Directory $name already exists") }
                return@launch
            }
            val authUrl = withGithubToken(trimmed)
            val result = graph.runtime.exec(
                listOf(
                    "bash", "-lc",
                    "git clone ${shellEscape(authUrl)} ${shellEscape(name)}",
                ),
                cwd = PRootCommand.WORKSPACE_MOUNT,
                timeoutMs = 10 * 60_000,
            )
            _state.update {
                if (result.exitCode == 0) {
                    it.copy(busy = null, message = "Cloned $name")
                } else {
                    it.copy(busy = null, message = result.output.takeLast(400))
                }
            }
            refresh()
            refreshGit()
        }
    }

    fun createFile(name: String) {
        _state.update { it.copy(showNewFileDialog = false) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val file = File(workspace, name.trim())
            if (file.exists()) {
                _state.update { it.copy(message = "${file.name} already exists") }
                return@launch
            }
            runCatching {
                if (name.endsWith("/")) file.mkdirs() else {
                    file.parentFile?.mkdirs()
                    file.writeText("")
                }
            }.onFailure { e -> _state.update { it.copy(message = e.message) } }
            refresh()
        }
    }

    private fun refreshGit() {
        if (!graph.layout.isInstalled()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val status = graph.runtime.exec(
                listOf("bash", "-lc", "git status --porcelain -b 2>&1 | head -50"),
                cwd = PRootCommand.WORKSPACE_MOUNT,
                timeoutMs = 30_000,
            )
            val notRepo = status.output.contains("not a git repository", ignoreCase = true)
            _state.update { st ->
                if (notRepo) {
                    st.copy(gitBranch = null, gitChanges = 0)
                } else {
                    val lines = status.output.lines()
                    val branch = lines.firstOrNull()?.removePrefix("## ")?.split("…")?.firstOrNull()
                    val changes = lines.drop(1).count { it.isNotBlank() }
                    st.copy(gitBranch = branch, gitChanges = changes)
                }
            }
        }
    }

    fun commit(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        if (!envReady()) return
        _state.update { it.copy(busy = "Committing…") }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val git = graph.settings.gitConfig()
            val identity = buildString {
                if (git.userName.isNotBlank()) append("-c user.name=${shellEscape(git.userName)} ")
                if (git.userEmail.isNotBlank()) append("-c user.email=${shellEscape(git.userEmail)} ")
            }
            val result = graph.runtime.exec(
                listOf(
                    "bash", "-lc",
                    "git add -A && git $identity commit -m ${shellEscape(trimmed)}",
                ),
                cwd = PRootCommand.WORKSPACE_MOUNT,
            )
            _state.update {
                if (result.exitCode == 0) {
                    it.copy(busy = null, message = "Committed")
                } else {
                    it.copy(busy = null, message = result.output.takeLast(400))
                }
            }
            refreshGit()
        }
    }

    fun push() {
        if (!envReady()) return
        _state.update { it.copy(busy = "Pushing…") }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val token = graph.settings.gitConfig().githubToken
            val result = graph.runtime.exec(
                listOf("bash", "-lc", buildPushCommand(token)),
                cwd = PRootCommand.WORKSPACE_MOUNT,
                timeoutMs = 5 * 60_000,
            )
            _state.update {
                if (result.exitCode == 0) {
                    it.copy(busy = null, message = "Pushed")
                } else {
                    it.copy(busy = null, message = result.output.takeLast(400))
                }
            }
        }
    }

    /**
     * Build `git push` with token auth injected for github.com remotes when a
     * PAT is configured. The whole URL is shell-quoted when executed.
     */
    private suspend fun buildPushCommand(token: String): String {
        val remoteResult = graph.runtime.exec(
            listOf("bash", "-lc", "git remote get-url origin"),
            cwd = PRootCommand.WORKSPACE_MOUNT,
        )
        val remote = remoteResult.output.trim()
        return if (token.isNotBlank() && remote.startsWith("https://github.com/")) {
            val authed = remote
                .replaceFirst("https://github.com/", "https://x-access-token:$token@github.com/")
            "git push ${shellEscape(authed)} HEAD"
        } else {
            "git push"
        }
    }

    private fun withGithubToken(url: String): String {
        val token = graph.settings.gitConfig().githubToken
        return if (token.isNotBlank() && url.startsWith("https://github.com/")) {
            url.replaceFirst(
                "https://github.com/",
                "https://x-access-token:$token@github.com/",
            )
        } else {
            url
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        super.onCleared()
    }
}
