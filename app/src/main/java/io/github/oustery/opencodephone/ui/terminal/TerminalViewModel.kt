package io.github.oustery.opencodephone.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.oustery.opencodephone.core.model.ConsoleOutput
import io.github.oustery.opencodephone.AppGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class TerminalUiState(
    val output: List<String> = emptyList(),
    val draft: String = "",
    val alive: Boolean = false,
    /** Recently sent commands, newest first (chips above the composer). */
    val history: List<String> = emptyList(),
    /** Terminal font scale (1.0 = stock); zoom persists for the session. */
    val fontScale: Float = 1.0f,
)

/**
 * Persistent interactive shell inside the Debian environment.
 *
 * The shell runs without a pseudo-terminal in v0.1 (see ADR-006): line output
 * and command execution work, but full-screen TUI programs (vim, htop) do not.
 */
class TerminalViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state

    @Volatile private var process: Process? = null
    private var shellJob: Job? = null
    @Volatile private var generation = 0
    private val processGuard = Any()
    private val output = ConsoleOutput(MAX_LINES)

    fun start() {
        if (shellJob?.isActive == true || process?.isAlive == true) return
        if (!graph.layout.isInstalled()) {
            // Without the rootfs proot only emits cryptic warnings; give a
            // human-readable hint instead of starting a doomed shell.
            appendLine("── ${graph.appContext.getString(io.github.oustery.opencodephone.R.string.terminal_env_not_ready)} ──")
            _state.update { it.copy(alive = false) }
            return
        }
        val run = ++generation
        // Shared project context (functional audit F-001): the shell starts in
        // the repository selected in Files instead of the workspace root.
        val project = graph.selectedProject.value
        val cwd = io.github.oustery.opencodephone.core.linuxenv.PRootCommand.WORKSPACE_MOUNT +
            if (project.isNullOrBlank()) "" else "/$project"
        shellJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val proc = graph.runtime.startInteractive(graph.runtime.process(
                    // stdin is a pipe, not a PTY. Interactive bash tries
                    // tcsetpgrp() and emits the warnings seen on Android.
                    listOf("/bin/bash", "--noprofile", "--norc", "-s"),
                    env = mapOf("TERM" to "dumb", "PAGER" to "cat", "GIT_PAGER" to "cat"),
                    cwd = cwd,
                    mergeOutput = true,
                ))
                val accepted = synchronized(processGuard) {
                    if (run != generation) false else {
                        process = proc
                        _state.update { it.copy(alive = true) }
                        true
                    }
                }
                if (!accepted) {
                    proc.destroy()
                    return@launch
                }
                appendLine("── OpenCodePhone shell (Debian) ──")
                if (!project.isNullOrBlank()) {
                    appendLine(
                        "── ${graph.appContext.getString(io.github.oustery.opencodephone.R.string.terminal_project)}: $project ──",
                    )
                }
                proc.inputStream.bufferedReader().use { reader ->
                    val chunk = CharArray(2048)
                    while (true) {
                        val size = reader.read(chunk)
                        if (size < 0) break
                        if (run == generation) appendOutput(String(chunk, 0, size))
                    }
                }
                synchronized(processGuard) {
                    if (run == generation && process === proc) {
                        process = null
                        appendLine("── shell exited ──")
                        _state.update { it.copy(alive = false) }
                    }
                }
            } catch (e: Exception) {
                synchronized(processGuard) {
                    if (run == generation) {
                        appendLine("── shell failed: ${e.message} ──")
                        _state.update { it.copy(alive = false) }
                    }
                }
            }
        }
    }

    fun updateDraft(value: String) = _state.update { it.copy(draft = value) }

    /** Full raw transcript for the copy-all action. */
    fun outputText(): String = _state.value.output.joinToString("\n")

    fun zoomIn() = _state.update { it.copy(fontScale = (it.fontScale + 0.1f).coerceAtMost(1.6f)) }
    fun zoomOut() = _state.update { it.copy(fontScale = (it.fontScale - 0.1f).coerceAtLeast(0.7f)) }

    /** Put a recalled history entry into the composer for editing. */
    fun recall(command: String) = _state.update { it.copy(draft = command) }

    fun send() {
        val text = _state.value.draft
        val proc = process ?: return
        if (!proc.isAlive || text.isBlank()) return
        appendLine("$ $text")
        // Keep a short de-duplicated history so recent commands stay one tap
        // away (and survive tab switches with the retained view model).
        _state.update { st -> st.copy(history = (listOf(text) + st.history.filter { it != text }).take(MAX_HISTORY)) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                proc.outputStream.write((text + "\n").toByteArray(Charsets.UTF_8))
                proc.outputStream.flush()
            }.onFailure { appendLine("── write failed: ${it.message}") }
        }
        _state.update { it.copy(draft = "") }
    }

    fun clear() {
        synchronized(output) {
            output.clear()
            _state.update { it.copy(output = emptyList()) }
        }
    }

    fun restart() {
        stop()
        start()
    }

    fun stop() {
        val old = synchronized(processGuard) {
            generation++
            val proc = process
            process = null
            _state.update { it.copy(alive = false) }
            proc
        }
        shellJob?.cancel()
        shellJob = null
        runCatching { old?.destroy() }
    }

    private fun appendLine(line: String) = appendOutput("$line\n")

    private fun appendOutput(text: String) {
        synchronized(output) {
            val snapshot = output.append(text)
            _state.update { it.copy(output = snapshot) }
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_LINES = 400
        const val MAX_HISTORY = 24
    }
}
