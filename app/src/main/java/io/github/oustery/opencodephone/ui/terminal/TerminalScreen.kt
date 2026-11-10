package io.github.oustery.opencodephone.ui.terminal

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import io.github.oustery.opencodephone.core.designsystem.OcChipTone
import io.github.oustery.opencodephone.core.designsystem.OcEmptyState
import io.github.oustery.opencodephone.core.designsystem.OcStatusChip
import io.github.oustery.opencodephone.ui.common.VmFactory

@Composable
fun TerminalScreen(graph: AppGraph) {
    val vm: TerminalViewModel = viewModel(factory = VmFactory.of { TerminalViewModel(graph) })
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.start() }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.tab_terminal),
            actions = {
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
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.weight(1f).fillMaxWidth(),
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
                                style = io.github.oustery.opencodephone.core.designsystem.CodeTypography.output,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            LaunchedEffect(state.output.size) {
                if (state.output.isNotEmpty()) {
                    listState.animateScrollToItem(state.output.size - 1)
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

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = { vm.updateDraft(it) },
                    placeholder = { Text(stringResource(R.string.terminal_input_hint)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.send() }),
                    textStyle = io.github.oustery.opencodephone.core.designsystem.CodeTypography.editor,
                    shape = MaterialTheme.shapes.small,
                )
                // Primary action keeps the same filled emphasis as the chat
                // send button (M3E audit E-008).
                FilledIconButton(
                    onClick = { vm.send() },
                    enabled = state.alive && state.draft.isNotBlank(),
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

private val QUICK_COMMANDS = listOf(
    "ls -la",
    "git status",
    "uname -a",
    "df -h",
    "opencode --version",
)
