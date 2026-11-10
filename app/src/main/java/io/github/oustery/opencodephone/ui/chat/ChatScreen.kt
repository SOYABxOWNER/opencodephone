package io.github.oustery.opencodephone.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.oustery.opencodephone.core.designsystem.OcActionChip
import io.github.oustery.opencodephone.core.designsystem.AppFonts
import io.github.oustery.opencodephone.core.designsystem.OcDisclosure
import io.github.oustery.opencodephone.core.designsystem.AppMessageShapes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.R
import io.github.oustery.opencodephone.ScreenBody
import io.github.oustery.opencodephone.ScreenHeader
import io.github.oustery.opencodephone.core.designsystem.OcChipTone
import io.github.oustery.opencodephone.core.designsystem.OcEmptyState
import io.github.oustery.opencodephone.core.designsystem.OcExpressiveSheet
import io.github.oustery.opencodephone.core.designsystem.OcShapeEmblem
import io.github.oustery.opencodephone.core.designsystem.OcSkeletonRows
import io.github.oustery.opencodephone.core.designsystem.OcStatusChip
import io.github.oustery.opencodephone.core.model.ModelEntry
import io.github.oustery.opencodephone.core.model.ProviderEntry
import io.github.oustery.opencodephone.core.model.QuestionRequest
import io.github.oustery.opencodephone.core.model.Session
import io.github.oustery.opencodephone.core.model.SessionFileDiff
import io.github.oustery.opencodephone.core.model.TodoItem
import io.github.oustery.opencodephone.core.model.UiPart
import io.github.oustery.opencodephone.ui.common.VmFactory
import java.net.URLDecoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(graph: AppGraph, onOpenSettings: () -> Unit) {
    val vm: ChatViewModel = viewModel(factory = VmFactory.of { ChatViewModel(graph) })
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.tab_chat),
            actions = {
                val selection = state.modelSelection
                OcActionChip(
                    text = selection?.modelId?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.chat_pick_model),
                    actionLabel = stringResource(R.string.chat_pick_model),
                    onClick = { vm.showModels(true) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.showDiff(true) }, enabled = state.connection == ChatConnection.CONNECTED) {
                    Icon(
                        painterResource(R.drawable.ic_diff),
                        contentDescription = stringResource(R.string.chat_diff_title),
                    )
                }
                IconButton(onClick = { vm.showSessions(true) }) {
                    Icon(
                        painterResource(R.drawable.ic_sessions),
                        contentDescription = stringResource(R.string.chat_sessions),
                    )
                }
            },
        )

        when (state.connection) {
            ChatConnection.CONNECTED -> ChatConnectedBody(vm, state)
            ChatConnection.CONNECTING -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Flagship expressive loader for a full-screen wait (E-005).
                LoadingIndicator(modifier = Modifier.size(64.dp))
                Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.chat_connecting), style = MaterialTheme.typography.bodyLarge)
            }
            ChatConnection.DISCONNECTED -> OcEmptyState(
                title = stringResource(R.string.chat_offline_title),
                description = stringResource(R.string.chat_offline_body),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                actionText = stringResource(R.string.chat_offline_settings),
                onAction = onOpenSettings,
            )
            ChatConnection.ERROR -> OcEmptyState(
                title = stringResource(R.string.chat_error_title),
                description = state.error ?: "",
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                actionText = stringResource(R.string.chat_offline_settings),
                onAction = onOpenSettings,
            )
        }

        if (state.showAgents) {
            OcExpressiveSheet(onDismissRequest = { vm.showAgents(false) }) {
                AgentsSheet(vm, state)
            }
        }
        if (state.showSessions) {
            OcExpressiveSheet(onDismissRequest = { vm.showSessions(false) }) {
                SessionsSheet(vm, state)
            }
        }
        if (state.showModels) {
            OcExpressiveSheet(onDismissRequest = { vm.showModels(false) }) {
                ModelsSheet(vm, state)
            }
        }
        if (state.showDiff) {
            OcExpressiveSheet(onDismissRequest = { vm.showDiff(false) }) {
                DiffSheet(vm, state)
            }
        }
    }
}

@Composable
private fun ChatConnectedBody(vm: ChatViewModel, state: ChatUiState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                Surface(modifier = Modifier.width(260.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    SessionsSheet(vm, state)
                }
                Column(Modifier.weight(1f)) { ChatTimeline(vm, state) }
            }
        } else ChatTimeline(vm, state)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatTimeline(vm: ChatViewModel, state: ChatUiState) {
    ScreenBody {
        val listState = rememberLazyListState()
        val clipboard = LocalClipboardManager.current
        // Revert confirmation (functional audit F-014): long-press on a user
        // message offers "revert the session to before this message".
        var revertFor by remember { mutableStateOf<UiMessage?>(null) }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.messages.isEmpty() && !state.busy) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Balanced expressive zone: slow shape morph above the
                        // empty timeline (M3E audit E-015). Errors and offline
                        // states keep the quiet OcEmptyState without decoration.
                        OcShapeEmblem(
                            icon = painterResource(R.drawable.ic_tab_chat),
                            animated = true,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        OcEmptyState(
                            title = stringResource(R.string.chat_empty_title),
                            description = stringResource(R.string.chat_empty_body),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(
                    message,
                    onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                    onRevert = if (message.role == "user") {
                        { revertFor = message }
                    } else null,
                )
            }
            // Older history is one tap away instead of being loaded wholesale
            // (functional audit F-011).
            if (state.earlierCursor != null) {
                item(key = "load-earlier") {
                    TextButton(
                        onClick = { vm.loadEarlier() },
                        enabled = !state.loadingEarlier,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (state.loadingEarlier) R.string.chat_loading_earlier else R.string.chat_load_earlier,
                            ),
                        )
                    }
                }
            }
        }

        LaunchedEffect(state.activeSessionId) { listState.scrollToItem(0) }
        LaunchedEffect(state.messages.firstOrNull()?.id) {
            // Do not drag readers away from older messages or animate every streamed token.
            if (listState.firstVisibleItemIndex <= 1 && !listState.isScrollInProgress) listState.scrollToItem(0)
        }
        if (state.busy) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())

        // Agent task plan for the active session (functional audit F-010).
        if (state.todos.isNotEmpty()) {
            TodosCard(state.todos)
        }

        // The session is reverted at a message (functional audit F-014):
        // later messages are hidden from the timeline and the context until
        // the user restores them.
        if (state.revertMarker != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.chat_revert_banner),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.unrevert() }) {
                        Text(stringResource(R.string.chat_unrevert))
                    }
                }
            }
        }

        AnimatedVisibility(visible = state.permission != null) {
            state.permission?.let { request ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.chat_permission_title, request.type),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        // The shell command (or path) the agent wants to run,
                        // when the tool carries one in `metadata.command`.
                        // A local copy is required: `command` is declared in
                        // another module, so no smart cast applies.
                        val command = request.command
                        if (!command.isNullOrBlank()) {
                            Text(
                                command,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = AppFonts.Code,
                            )
                        } else if (request.title.isNotBlank()) {
                            Text(request.title, style = MaterialTheme.typography.bodySmall)
                        }
                        // Action hierarchy instead of three equal text buttons
                        // (UI audit C7): the recommended once-grant is the
                        // primary action, "always" is the quieter outline
                        // variant, and the rejection carries the error tone.
                        // A wrapping row keeps the longer ru labels intact on
                        // narrow screens.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilledTonalButton(onClick = { vm.respondPermission("once") }) {
                                Text(stringResource(R.string.chat_permission_once))
                            }
                            OutlinedButton(onClick = { vm.respondPermission("always") }) {
                                Text(stringResource(R.string.chat_permission_always))
                            }
                            TextButton(
                                onClick = { vm.respondPermission("reject") },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(stringResource(R.string.chat_permission_deny))
                            }
                        }
                    }
                }
            }
        }

        state.error?.let { raw ->
            // Frequent network failure categories get a localized headline;
            // the raw exception text stays below as the secondary detail
            // (UI audit C2). Unrecognized messages keep the raw-only banner.
            val headline = when {
                raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                    stringResource(R.string.chat_error_timeout_title)
                raw.contains("401") || raw.contains("unauthorized", ignoreCase = true) ->
                    stringResource(R.string.chat_error_auth_title)
                raw.contains("403") || raw.contains("forbidden", ignoreCase = true) ->
                    stringResource(R.string.chat_error_access_title)
                raw.contains("refused", ignoreCase = true) ||
                    raw.contains("failed to connect", ignoreCase = true) ||
                    raw.contains("unable to resolve", ignoreCase = true) ->
                    stringResource(R.string.chat_error_connect_title)
                else -> null
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (headline == null) {
                            Text(raw, style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(headline, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                raw,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(onClick = { vm.dismissError() }) { Text(stringResource(R.string.action_dismiss)) }
                }
            }
        }

        ChatInput(vm, state)

        // Agent questions (v1.19.5 `question` tool): a modal answer dialog
        // because the agent blocks on the reply until it is answered or
        // dismissed. Dismissal outside the buttons also rejects the request.
        state.question?.let { request ->
            QuestionDialog(
                request = request,
                onAnswer = vm::answerQuestion,
                onDismiss = vm::dismissQuestion,
            )
        }

        revertFor?.let { target ->
            AlertDialog(
                onDismissRequest = { revertFor = null },
                title = { Text(stringResource(R.string.chat_revert_title)) },
                text = { Text(stringResource(R.string.chat_revert_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        revertFor = null
                        vm.revertTo(target.id)
                    }) { Text(stringResource(R.string.chat_revert_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { revertFor = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatInput(vm: ChatViewModel, state: ChatUiState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.attachUri(uri)
    }
    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
        // Shared project context (functional audit F-001) next to the agent
        // picker (functional audit F-006), both above the composer.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.projectContext?.let { project ->
                OcStatusChip(
                    text = stringResource(R.string.chat_project_chip, project.substringAfterLast('/')),
                    tone = OcChipTone.NEUTRAL,
                )
            }
            AgentChip(
                agentName = state.agentName,
                onClick = { vm.showAgents(true) },
            )
        }
        if (state.attachments.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.attachments, key = { it.id }) { file ->
                    AttachmentChip(file) { vm.removeAttachment(file.id) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !state.busy,
                modifier = Modifier.padding(bottom = 2.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_attach),
                    contentDescription = stringResource(R.string.chat_attach),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = state.draft,
                onValueChange = { vm.updateDraft(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                shape = AppMessageShapes.composer,
                maxLines = 4,
            )
            // Colors morph between send/stop instead of snapping; the icon
            // crossfades inside a stable frame (M3E audit E-004).
            val haptics = LocalHapticFeedback.current
            val sendContainer by animateColorAsState(
                targetValue = if (state.busy) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                animationSpec = tween(durationMillis = 220),
                label = "send-button-container",
            )
            val sendContent by animateColorAsState(
                targetValue = if (state.busy) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                animationSpec = tween(durationMillis = 220),
                label = "send-button-content",
            )
            FilledIconButton(
                onClick = {
                    // Send/abort confirmation (M3E audit E-010).
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (state.busy) vm.abort() else vm.send()
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = sendContainer,
                    contentColor = sendContent,
                ),
            ) {
                AnimatedContent(targetState = state.busy, label = "send-stop-icon") { busy ->
                    Icon(
                        painterResource(if (busy) R.drawable.ic_stop else R.drawable.ic_send),
                        contentDescription = if (busy) {
                            stringResource(R.string.chat_stop)
                        } else {
                            stringResource(R.string.chat_send)
                        },
                    )
                }
            }
        }
    }
}

/** Pending attachment above the composer; removing it deletes the workspace copy. */
@Composable
private fun AttachmentChip(file: PendingAttachment, onRemove: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_file),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Column {
                Text(
                    file.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                Text(
                    formatAttachmentSize(file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            // No size() override on the button: the 16dp icon keeps the row
            // compact while the standard control bounds restore a 48dp touch
            // target (UI audit A1).
            IconButton(onClick = onRemove) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.chat_attachment_remove),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun formatAttachmentSize(bytes: Long): String {
    val text = when {
        bytes >= 1024 * 1024 -> stringResource(R.string.chat_size_mb, String.format("%.1f", bytes / (1024f * 1024f)))
        bytes >= 1024 -> stringResource(R.string.chat_size_kb, String.format("%.1f", bytes / 1024f))
        else -> stringResource(R.string.chat_size_b, bytes.toString())
    }
    return text
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: UiMessage, onCopy: (String) -> Unit, onRevert: (() -> Unit)?) {
    val isUser = message.role == "user"
    val revertHint = stringResource(R.string.chat_revert_action)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val bubbleWidth = (maxWidth * 0.9f).coerceAtMost(560.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = if (isUser) AppMessageShapes.outgoing else AppMessageShapes.incoming,
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = bubbleWidth)
                // Long-press on a user message offers the revert action (F-014).
                .then(
                    if (onRevert != null) {
                        // Long-press stays as the shortcut; the audit flagged
                        // it as unreliable next to the selection container, so
                        // the row button above is the authoritative affordance
                        // (UI audit C9).
                        Modifier.combinedClickable(
                            role = Role.Button,
                            onClick = {},
                            onLongClick = onRevert,
                            onLongClickLabel = revertHint,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message.parts.forEach { part -> PartView(part, onCopy, onRevert) }
            }
        }
    }
}
}

@Composable
private fun PartView(part: UiPart, onCopy: (String) -> Unit, onRevert: (() -> Unit)? = null) {
    when (part) {
        is UiPart.Text -> Column {
            SelectionText(part.text)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // Full touch target again (UI audit A1); compactness lives in
                // the 15dp icon, not in a shrunk button.
                androidx.compose.material3.IconButton(
                    onClick = { onCopy(part.text) },
                ) {
                    Icon(
                        painterResource(R.drawable.ic_copy),
                        contentDescription = stringResource(R.string.action_copy),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Explicit revert affordance (UI audit C9): the bubble's
                // long-press shortcut competes with text selection on user
                // messages, so the guaranteed path to revert is this button.
                if (onRevert != null) {
                    androidx.compose.material3.IconButton(onClick = onRevert) {
                        Icon(
                            painterResource(R.drawable.ic_revert),
                            contentDescription = stringResource(R.string.chat_revert_action),
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        is UiPart.Reasoning -> OcDisclosure(
            title = stringResource(R.string.chat_reasoning),
            expandedDescription = stringResource(R.string.state_expanded),
            collapsedDescription = stringResource(R.string.state_collapsed),
        ) {
            // Copyable like the answer text (M3E audit E-011).
            SelectionContainer {
                Text(part.text, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is UiPart.FileAttachment -> AttachmentPartView(part)
        is UiPart.Tool -> ToolCard(part)
        UiPart.Unknown -> Unit
        is UiPart.Step -> Unit
    }
}

/** A `file` part stored in the message history. */
@Composable
private fun AttachmentPartView(part: UiPart.FileAttachment) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_attach),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                part.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolCard(part: UiPart.Tool) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    painterResource(R.drawable.ic_tab_terminal),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(part.tool, style = MaterialTheme.typography.labelLarge)
                val statusTone = when (part.status) {
                    "completed" -> OcChipTone.PRIMARY
                    "error" -> OcChipTone.ERROR
                    "pending", "running" -> OcChipTone.TERTIARY
                    else -> OcChipTone.NEUTRAL
                }
                // Localized chip label instead of the raw English server status
                // (UI audit C1); unknown statuses still render verbatim.
                val statusLabel = when (part.status) {
                    "completed" -> stringResource(R.string.chat_status_completed)
                    "error" -> stringResource(R.string.chat_status_error)
                    "pending" -> stringResource(R.string.chat_status_pending)
                    "running" -> stringResource(R.string.chat_status_running)
                    else -> part.status
                }
                OcStatusChip(text = statusLabel, tone = statusTone)
            }
            // Local copies are required: title/output live in another Gradle
            // module, so the compiler will not smart-cast the properties.
            val title = part.title
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val output = part.output
            if (!output.isNullOrBlank()) {
                OcDisclosure(
                    title = stringResource(R.string.chat_tool_output),
                    expandedDescription = stringResource(R.string.state_expanded),
                    collapsedDescription = stringResource(R.string.state_collapsed),
                ) {
                    // Copyable without a toolbar (M3E audit E-011).
                    TruncatedCodeText(output, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Failure explanation instead of a bare error chip (F-012).
            val error = part.error
            if (part.status == "error" && !error.isNullOrBlank()) {
                OcDisclosure(
                    title = stringResource(R.string.chat_tool_error),
                    expandedDescription = stringResource(R.string.state_expanded),
                    collapsedDescription = stringResource(R.string.state_collapsed),
                ) {
                    TruncatedCodeText(error, MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Cap before the tool output/error preview is cut (UI audit C3). */
private const val TOOL_OUTPUT_LIMIT = 4000

/**
 * Mono code text that says so when it was silently cut at [TOOL_OUTPUT_LIMIT]
 * (UI audit C3) — the notice is a separate line, so copying keeps the raw
 * output untouched.
 */
@Composable
private fun TruncatedCodeText(text: String, color: Color) {
    SelectionContainer {
        Text(
            text.take(TOOL_OUTPUT_LIMIT),
            style = io.github.oustery.opencodephone.core.designsystem.CodeTypography.output,
            color = color,
        )
    }
    if (text.length > TOOL_OUTPUT_LIMIT) {
        Text(
            stringResource(R.string.chat_tool_output_truncated, TOOL_OUTPUT_LIMIT),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** Selectable plain text preserving line breaks. */
@Composable
private fun SelectionText(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SessionsSheet(vm: ChatViewModel, state: ChatUiState) {
    // Hoisted: LocalHapticFeedback.current is a composable call and must not
    // run inside the row click lambda (M3E audit E-010).
    val haptics = LocalHapticFeedback.current
    // Session management actions (functional audit F-004).
    var pendingDelete by remember { mutableStateOf<Session?>(null) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.chat_sessions), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = state.sessionQuery,
            onValueChange = { vm.updateSessionQuery(it) },
            placeholder = { Text(stringResource(R.string.chat_sessions_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.size(8.dp))
        if (state.sessionsLoading) {
            // First fetch in flight — placeholder rows instead of a false
            // "no sessions" label (M3E audit E-017).
            Text(
                stringResource(R.string.chat_sessions_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            OcSkeletonRows(rows = 3)
        } else if (state.sessions.isEmpty()) {
            Text(
                stringResource(R.string.chat_sessions_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val query = state.sessionQuery.trim()
        val visible = if (query.isEmpty()) state.sessions else state.sessions.filter {
            it.title.contains(query, ignoreCase = true)
        }
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
          items(visible, key = { it.id }) { session ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (session.id == state.activeSessionId) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp).semantics { selected = session.id == state.activeSessionId }.clickable(role = Role.Button) {
                    // Selection confirmation (M3E audit E-010).
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.selectSession(session.id)
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.title.ifBlank { stringResource(R.string.chat_session_untitled) },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(start = 14.dp, top = 14.dp, bottom = 14.dp),
                    )
                    // size() overrides never shrink the icon below what the row
                    // needs, they only shrink the touch target (UI audit A1).
                    IconButton(onClick = { renaming = session }) {
                        Icon(
                            painterResource(R.drawable.ic_edit),
                            contentDescription = stringResource(R.string.chat_session_rename),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { pendingDelete = session }) {
                        Icon(
                            painterResource(R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.action_delete),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        }
        if (query.isNotEmpty() && visible.isEmpty() && state.sessions.isNotEmpty()) {
            Text(
                stringResource(R.string.chat_sessions_no_match),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.sessions.size >= CHAT_SESSIONS_LIMIT) {
            Text(
                stringResource(R.string.chat_sessions_limit, CHAT_SESSIONS_LIMIT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        androidx.compose.material3.Button(onClick = { vm.newSession() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.chat_new_session))
        }
        Spacer(Modifier.size(24.dp))
    }
    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_session_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.chat_session_delete_body,
                        session.title.ifBlank { stringResource(R.string.chat_session_untitled) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSession(session.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    renaming?.let { session ->
        RenameSessionDialog(
            initial = session.title,
            onSave = {
                vm.renameSession(session.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun RenameSessionDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_session_rename)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text(stringResource(R.string.chat_session_rename_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(title) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Modal dialog for the pending agent question (v1.19.5 `question` tool).
 * One request can carry several questions: single-choice ones render as
 * radio buttons, `multiple` ones as checkboxes, and every question accepts
 * a typed custom answer unless the server disables it (custom=false).
 * A typed custom answer takes precedence over selected options.
 */
@Composable
private fun QuestionDialog(
    request: QuestionRequest,
    onAnswer: (List<List<String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selections = remember(request.id) {
        List(request.questions.size) { mutableStateOf(emptySet<Int>()) }
    }
    val customs = remember(request.id) {
        List(request.questions.size) { mutableStateOf("") }
    }
    // "Answer" stays disabled until every question has an explicit answer —
    // a picked option or typed custom text (UI audit B1): an empty reply to a
    // single-choice question is useless to the agent. Skip remains available.
    val canAnswer = request.questions.indices.all { index ->
        customs[index].value.isNotBlank() || selections[index].value.isNotEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val single = request.questions.size == 1
            val header = request.questions.firstOrNull()?.header.orEmpty()
            Text(
                if (single && header.isNotBlank()) header
                else stringResource(R.string.chat_question_title),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                request.questions.forEachIndexed { index, info ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (request.questions.size > 1) {
                            Text(
                                info.header.ifBlank { "${index + 1}" },
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Text(info.question, style = MaterialTheme.typography.bodyMedium)
                        if (info.multiple == true) {
                            Text(
                                stringResource(R.string.chat_question_multi_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(modifier = Modifier.selectableGroup()) {
                            info.options.forEachIndexed { optionIndex, option ->
                                val selected = selections[index].value.contains(optionIndex)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = selected,
                                            role = if (info.multiple == true) Role.Checkbox else Role.RadioButton,
                                            onClick = {
                                                val current = selections[index].value
                                                selections[index].value = if (info.multiple == true) {
                                                    if (selected) current - optionIndex else current + optionIndex
                                                } else {
                                                    setOf(optionIndex)
                                                }
                                            },
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (info.multiple == true) {
                                        Checkbox(checked = selected, onCheckedChange = null)
                                    } else {
                                        RadioButton(selected = selected, onClick = null)
                                    }
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                        if (option.description.isNotBlank()) {
                                            Text(
                                                option.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (info.custom != false) {
                            OutlinedTextField(
                                value = customs[index].value,
                                onValueChange = { customs[index].value = it },
                                label = { Text(stringResource(R.string.chat_question_custom)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                            )
                        }
                    }
                }
                if (!canAnswer) {
                    Text(
                        stringResource(R.string.chat_question_answer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val answers = request.questions.mapIndexed { index, info ->
                        val custom = customs[index].value.trim()
                        val labels = selections[index].value.mapNotNull { info.options.getOrNull(it)?.label }
                        when {
                            custom.isNotBlank() && info.multiple == true -> labels + custom
                            custom.isNotBlank() -> listOf(custom)
                            else -> labels
                        }
                    }
                    onAnswer(answers)
                },
                enabled = canAnswer,
            ) {
                Text(stringResource(R.string.chat_question_answer))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_question_skip))
            }
        },
    )
}

/** Per-session change review backed by `GET /session/:id/diff` (F-005). */
@Composable
private fun DiffSheet(vm: ChatViewModel, state: ChatUiState) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.chat_diff_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(4.dp))
        if (state.diffLoading) {
            Spacer(Modifier.size(8.dp))
            OcSkeletonRows(rows = 4)
        } else if (state.diff.isEmpty()) {
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.chat_diff_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.chat_diff_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.diff, key = { "${it.file}:${it.additions}:${it.deletions}" }) { entry ->
                    DiffFileRow(entry)
                }
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun DiffFileRow(entry: SessionFileDiff) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = entry.file.substringAfterLast('/').ifEmpty { entry.file },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                entry.status?.let { status ->
                    val tone = when (status) {
                        "added" -> OcChipTone.PRIMARY
                        "deleted" -> OcChipTone.ERROR
                        "modified" -> OcChipTone.TERTIARY
                        else -> OcChipTone.NEUTRAL
                    }
                    // Localized chip label instead of the raw English server
                    // status (UI audit C1); unknown statuses render verbatim.
                    val label = when (status) {
                        "added" -> stringResource(R.string.chat_diff_added)
                        "deleted" -> stringResource(R.string.chat_diff_deleted)
                        "modified" -> stringResource(R.string.chat_diff_modified)
                        else -> status
                    }
                    OcStatusChip(text = label, tone = tone)
                }
                Text(
                    text = "+${entry.additions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "-${entry.deletions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val patch = entry.patch
            if (!patch.isNullOrBlank()) {
                OcDisclosure(
                    title = stringResource(R.string.chat_diff_patch),
                    expandedDescription = stringResource(R.string.state_expanded),
                    collapsedDescription = stringResource(R.string.state_collapsed),
                ) {
                    SelectionContainer {
                        Text(
                            patch,
                            style = io.github.oustery.opencodephone.core.designsystem.CodeTypography.output,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelsSheet(vm: ChatViewModel, state: ChatUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.chat_models_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.chat_models_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Server version moved here from the permanent timeline layer, where
        // it occupied a line under every chat (UI audit C5).
        Text(
            stringResource(R.string.chat_connected, state.version),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        if (state.providersLoading) {
            Text(
                stringResource(R.string.chat_models_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            OcSkeletonRows(rows = 5)
        } else if (state.providers.isEmpty()) {
            Text(
                stringResource(R.string.chat_models_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            state.providers.forEach { provider ->
                item(key = "provider:${provider.id}") {
                    val connected = provider.id in state.connectedProviders
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(provider.name, style = MaterialTheme.typography.titleSmall)
                        OcStatusChip(
                            text = if (connected) stringResource(R.string.chat_provider_connected)
                            else stringResource(R.string.chat_provider_no_key),
                            tone = if (connected) OcChipTone.PRIMARY else OcChipTone.NEUTRAL,
                        )
                    }
                }
                items(
                    provider.models.values.sortedWith(
                        compareBy<ModelEntry> { !io.github.oustery.opencodephone.core.model.OpenCodeCatalog.isFree(it) }
                            .thenBy { it.name },
                    ),
                    key = { "model:${provider.id.length}:${provider.id}:${it.id}" },
                ) { model ->
                    ModelRow(
                        model = model,
                        isSelected = state.modelSelection?.modelId == model.id &&
                            state.modelSelection?.providerId == provider.id,
                        onClick = { vm.selectModel(provider.id, model.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: ModelEntry, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // Same selected-state semantics as session rows (M3E audit E-014).
            .semantics { selected = isSelected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (io.github.oustery.opencodephone.core.model.OpenCodeCatalog.isFree(model)) {
            OcStatusChip(text = stringResource(R.string.chat_model_free), tone = OcChipTone.NEUTRAL)
        }
        if (isSelected) {
            OcStatusChip(text = stringResource(R.string.chat_model_selected), tone = OcChipTone.PRIMARY)
        }
    }
}

/** Composer chip opening the agent picker (functional audit F-006). */
@Composable
private fun AgentChip(agentName: String?, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_tab_terminal),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                agentName ?: stringResource(R.string.chat_agent_default),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
    }
}

/** Agent picker backed by `GET /agent`; primary agents only (F-006). */
@Composable
private fun AgentsSheet(vm: ChatViewModel, state: ChatUiState) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.chat_agent_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.chat_agent_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        if (state.agents.isEmpty()) {
            Text(
                stringResource(R.string.chat_agent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            item(key = "agent:default") {
                AgentRow(
                    name = null,
                    isSelected = state.agentName == null,
                    onClick = { vm.selectAgent(null) },
                )
            }
            items(state.agents, key = { "agent:${it.name}" }) { agent ->
                AgentRow(
                    name = agent.name,
                    isSelected = state.agentName == agent.name,
                    onClick = { vm.selectAgent(agent.name) },
                )
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun AgentRow(name: String?, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { selected = isSelected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name ?: stringResource(R.string.chat_agent_default),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            OcStatusChip(text = stringResource(R.string.chat_model_selected), tone = OcChipTone.PRIMARY)
        }
    }
}

/** The agent's task plan for the active session (functional audit F-010). */
@Composable
private fun TodosCard(todos: List<TodoItem>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.chat_todos_title), style = MaterialTheme.typography.titleSmall)
            todos.forEach { todo ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val done = todo.status == "completed" || todo.status == "cancelled"
                    val (icon, tint) = when (todo.status) {
                        "completed" -> R.drawable.ic_check to MaterialTheme.colorScheme.primary
                        "in_progress" -> R.drawable.ic_refresh to MaterialTheme.colorScheme.tertiary
                        "cancelled" -> R.drawable.ic_close to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> R.drawable.ic_chevron_right to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    // Localized state for TalkBack instead of the raw English
                    // server status (UI/UX stage D).
                    val statusLabel = when (todo.status) {
                        "completed" -> stringResource(R.string.chat_todo_done)
                        "in_progress" -> stringResource(R.string.chat_todo_in_progress)
                        "cancelled" -> stringResource(R.string.chat_todo_cancelled)
                        else -> stringResource(R.string.chat_todo_pending)
                    }
                    Icon(
                        painterResource(icon),
                        contentDescription = statusLabel,
                        modifier = Modifier.size(16.dp),
                        tint = tint,
                    )
                    Text(
                        todo.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (done) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
