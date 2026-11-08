package io.github.oustery.opencodephone.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.oustery.opencodephone.AppGraph
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

    private var process: Process? = null
    private val lines = ArrayDeque<String>(MAX_LINES)

    fun start() {
        if (process?.isAlive == true) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val proc = graph.runtime.process(
                    listOf("bash", "-i"),
                    cwd = io.github.oustery.opencodephone.core.linuxenv.PRootCommand.WORKSPACE_MOUNT,
                    mergeOutput = true,
                ).start()
                process = proc
                _state.update { it.copy(alive = true) }
                appendLine("── OpenCodePhone shell (Debian) ──")
                proc.inputStream.bufferedReader().forEachLine { line ->
                    appendLine(line)
                }
                appendLine("── shell exited ──")
                _state.update { it.copy(alive = false) }
            } catch (e: Exception) {
                appendLine("── failed to start shell: ${e.message} ──")
                _state.update { it.copy(alive = false) }
            }
        }
    }

    fun updateDraft(value: String) = _state.update { it.copy(draft = value) }

    fun send() {
        val text = _state.value.draft
        val proc = process ?: return
        if (!proc.isAlive) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                proc.outputStream.write((text + "\n").toByteArray(Charsets.UTF_8))
                proc.outputStream.flush()
            }.onFailure { appendLine("── write failed: ${it.message}") }
        }
        _state.update { it.copy(draft = "") }
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        _state.update { it.copy(output = emptyList()) }
    }

    fun restart() {
        stop()
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            start()
        }
    }

    fun stop() {
        runCatching {
            process?.destroy()
        }
        process = null
    }

    private fun appendLine(line: String) {
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            _state.update { it.copy(output = lines.toList()) }
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_LINES = 400
    }
}
