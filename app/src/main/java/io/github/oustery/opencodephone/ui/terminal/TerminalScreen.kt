package io.github.oustery.opencodephone.ui.terminal

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.R
import io.github.oustery.opencodephone.ScreenBody
import io.github.oustery.opencodephone.ScreenHeader
import io.github.oustery.opencodephone.core.designsystem.CodeTypography
import io.github.oustery.opencodephone.core.designsystem.OcChipTone
import io.github.oustery.opencodephone.core.designsystem.OcEmptyState
import io.github.oustery.opencodephone.core.designsystem.OcStatusChip
import io.github.oustery.opencodephone.ui.common.VmFactory

@Composable
fun TerminalScreen(graph: AppGraph) {
    val vm: TerminalViewModel = viewModel(factory = VmFactory.of { TerminalViewModel(graph) })
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { vm.start() }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.tab_terminal),
            actions = {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(vm.outputText()))
                }) {
                    Icon(
                        painterResource(R.drawable.ic_copy),
                        contentDescription = stringResource(R.string.terminal_copy_all),
                    )
                }
                IconButton(onClick = { vm.clear() }) {
                    Icon(
                        painterResource(R.drawable.ic_clear_output),
                        contentDescription = stringResource(R.string.terminal_clear),
                    )
                }
                IconButton(onClick = { vm.restart() }) {
                    Icon(
                        painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.terminal_restart),
                    )
                }
            },
        )

        ScreenBody {
            val listState = rememberLazyListState()
            // Console chrome: a labelled strip (shell liveness dot, font zoom)
            // sits on top of the rounded output pane, so the terminal reads as
            // one instrument instead of a raw text list.
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Liveness dot: filled while the shell accepts input.
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.alive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                        )
                        Text(
                            text = stringResource(R.string.terminal_shell_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.zoomOut() }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text("A-", style = MaterialTheme.typography.labelLarge)
                        }
                        TextButton(onClick = { vm.zoomIn() }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text("A+", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        if (state.output.isEmpty()) {
                            item(key = "terminal-empty") {
                                OcEmptyState(
                                    title = stringResource(R.string.terminal_empty_title),
                                    description = stringResource(R.string.terminal_empty_hint),
                                    icon = painterResource(R.drawable.ic_tab_terminal),
                                )
                            }
                        }
                        items(state.output) { line ->
                            // Copyable output without a toolbar (M3E audit E-011).
                            SelectionContainer {
                                Text(
                                    text = line,
                                    style = CodeTypography.output.copy(
                                        fontSize = CodeTypography.output.fontSize * state.fontScale,
                                        lineHeight = CodeTypography.output.lineHeight * state.fontScale,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            // Follow the stream only when the reader is already at the bottom
            // and not scrolling (UI audit A3) — the unconditional jump made the
            // log impossible to inspect while a command was still producing
            // output. Mirrors the chat timeline behaviour.
            LaunchedEffect(state.output.size) {
                if (state.output.isEmpty()) return@LaunchedEffect
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                val atBottom = lastVisible >= info.totalItemsCount - 2
                if (atBottom && !listState.isScrollInProgress) {
                    listState.scrollToItem(state.output.size - 1)
                }
            }

            // Quiet feedback while the shell is down (e.g. after `exit`) —
            // the disabled send button alone never explains why.
            if (!state.alive) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    OcStatusChip(
                        text = stringResource(R.string.terminal_shell_stopped),
                        tone = OcChipTone.NEUTRAL,
                        icon = painterResource(R.drawable.ic_refresh),
                    )
                }
            }

            if (state.history.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.history) { command ->
                        // Recall fills the composer for editing instead of
                        // firing blindly — safer for destructive commands.
                        SuggestionChip(
                            onClick = { vm.recall(command) },
                            label = {
                                Text(command, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            },
                        )
                    }
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(QUICK_COMMANDS) { command ->
                    SuggestionChip(
                        onClick = {
                            vm.updateDraft(command)
                            vm.send()
                        },
                        label = { Text(command, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            // Docked composer, mirroring the chat composer surface.
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = { vm.updateDraft(it) },
                        placeholder = { Text(stringResource(R.string.terminal_input_hint)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { vm.send() }),
                        textStyle = CodeTypography.editor.copy(
                            fontSize = CodeTypography.editor.fontSize * state.fontScale,
                        ),
                        shape = MaterialTheme.shapes.extraLarge,
                        // The dock carries the chrome; the field itself stays
                        // quiet (border appears only on focus).
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                        ),
                    )
                    // Primary action keeps the same filled emphasis as the chat
                    // send button (M3E audit E-008).
                    FilledIconButton(
                        onClick = { vm.send() },
                        enabled = state.alive && state.draft.isNotBlank(),
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_send),
                            contentDescription = stringResource(R.string.terminal_run),
                        )
                    }
                }
            }
        }
    }
}

private val QUICK_COMMANDS = listOf(
    "ls -la",
    "git status",
    "git log --oneline -5",
    "uname -a",
    "df -h",
    "rg --version",
    "python3 --version",
    "node --version",
    "opencode --version",
)
