package io.github.oustery.opencodephone.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.R
import io.github.oustery.opencodephone.ScreenBody
import io.github.oustery.opencodephone.ScreenHeader
import io.github.oustery.opencodephone.core.designsystem.OcChipTone
import io.github.oustery.opencodephone.core.designsystem.OcEmptyState
import io.github.oustery.opencodephone.core.designsystem.OcStatusChip
import io.github.oustery.opencodephone.core.model.ModelEntry
import io.github.oustery.opencodephone.core.model.ProviderEntry
import io.github.oustery.opencodephone.core.model.UiPart
import io.github.oustery.opencodephone.ui.common.VmFactory
import java.net.URLDecoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(graph: AppGraph) {
    val vm: ChatViewModel = viewModel(factory = VmFactory.of { ChatViewModel(graph) })
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.tab_chat),
            actions = {
                val selection = state.modelSelection
                OcStatusChip(
                    text = selection?.modelId?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.chat_pick_model),
                    tone = if (selection != null) OcChipTone.PRIMARY else OcChipTone.NEUTRAL,
                    modifier = Modifier.clickable { vm.showModels(true) },
                )
                IconButton(onClick = { vm.showSessions(true) }) {
                    Icon(
                        painterResource(R.drawable.ic_tab_files),
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
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.chat_connecting), style = MaterialTheme.typography.bodyLarge)
            }
            ChatConnection.DISCONNECTED -> OcEmptyState(
                title = stringResource(R.string.chat_offline_title),
                description = stringResource(R.string.chat_offline_body),
                modifier = Modifier.fillMaxSize(),
                actionText = stringResource(R.string.chat_offline_settings),
            )
            ChatConnection.ERROR -> OcEmptyState(
                title = stringResource(R.string.chat_error_title),
                description = state.error ?: "",
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (state.showSessions) {
            ModalBottomSheet(onDismissRequest = { vm.showSessions(false) }) {
                SessionsSheet(vm, state)
            }
        }
        if (state.showModels) {
            ModalBottomSheet(onDismissRequest = { vm.showModels(false) }) {
                ModelsSheet(vm, state)
            }
        }
    }
}

@Composable
private fun ChatConnectedBody(vm: ChatViewModel, state: ChatUiState) {
    ScreenBody {
        val listState = rememberLazyListState()
        val clipboard = LocalClipboardManager.current

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    OcStatusChip(
                        text = stringResource(R.string.chat_connected, state.version),
                        tone = OcChipTone.PRIMARY,
                    )
                }
            }
            if (state.messages.isEmpty() && !state.busy) {
                item {
                    OcEmptyState(
                        title = stringResource(R.string.chat_empty_title),
                        description = stringResource(R.string.chat_empty_body),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message, onCopy = { text -> clipboard.setText(AnnotatedString(text)) })
            }
            if (state.busy) {
                item {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        LaunchedEffect(state.messages.size) {
            if (state.messages.isNotEmpty()) {
                listState.animateScrollToItem(0)
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
                        if (request.title.isNotBlank()) {
                            Text(request.title, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.respondPermission("once") }) {
                                Text(stringResource(R.string.chat_permission_once))
                            }
                            TextButton(onClick = { vm.respondPermission("always") }) {
                                Text(stringResource(R.string.chat_permission_always))
                            }
                            TextButton(onClick = { vm.respondPermission("reject") }) {
                                Text(stringResource(R.string.chat_permission_deny))
                            }
                        }
                    }
                }
            }
        }

        state.error?.let { error ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.dismissError() }) { Text(stringResource(R.string.action_dismiss)) }
                }
            }
        }

        ChatInput(vm, state)
    }
}

@Composable
private fun ChatInput(vm: ChatViewModel, state: ChatUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = { vm.updateDraft(it) },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
        )
        FilledIconButton(
            onClick = { if (state.busy) vm.abort() else vm.send() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (state.busy) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (state.busy) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            ),
        ) {
            Icon(
                painterResource(if (state.busy) R.drawable.ic_stop else R.drawable.ic_send),
                contentDescription = if (state.busy) {
                    stringResource(R.string.chat_stop)
                } else {
                    stringResource(R.string.chat_send)
                },
            )
        }
    }
}

@Composable
private fun MessageBubble(message: UiMessage, onCopy: (String) -> Unit) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 20.dp,
            ),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message.parts.forEach { part -> PartView(part, onCopy) }
            }
        }
    }
}

@Composable
private fun PartView(part: UiPart, onCopy: (String) -> Unit) {
    when (part) {
        is UiPart.Text -> Column {
            SelectionText(part.text)
            TextButton(onClick = { onCopy(part.text) }) {
                Text(stringResource(R.string.action_copy), style = MaterialTheme.typography.labelSmall)
            }
        }
        is UiPart.Reasoning -> Text(
            text = part.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is UiPart.Tool -> ToolCard(part)
        UiPart.Unknown -> Unit
        is UiPart.Step -> Unit
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolCard(part: UiPart.Tool) {
    val expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable { expanded.value = !expanded.value },
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
                OcStatusChip(text = part.status, tone = statusTone)
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
            if (expanded.value && !output.isNullOrBlank()) {
                Text(
                    text = output.take(4000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.chat_sessions), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        if (state.sessions.isEmpty()) {
            Text(
                stringResource(R.string.chat_sessions_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.sessions.forEach { session ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (session.id == state.activeSessionId) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                    vm.selectSession(session.id)
                },
            ) {
                Text(
                    text = session.title.ifBlank { stringResource(R.string.chat_session_untitled) },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        FilledIconButton(onClick = { vm.newSession() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.chat_new_session))
        }
        Spacer(Modifier.size(24.dp))
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
        Spacer(Modifier.size(8.dp))
        if (state.providers.isEmpty()) {
            Text(
                stringResource(R.string.chat_models_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            items(state.providers, key = { it.id }) { provider ->
                val connected = provider.id in state.connectedProviders
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(provider.name, style = MaterialTheme.typography.titleSmall)
                        OcStatusChip(
                            text = if (connected) stringResource(R.string.chat_provider_connected)
                            else stringResource(R.string.chat_provider_no_key),
                            tone = if (connected) OcChipTone.PRIMARY else OcChipTone.NEUTRAL,
                        )
                    }
                    provider.models.values.take(12).forEach { model ->
                        ModelRow(
                            model = model,
                            selected = state.modelSelection?.modelId == model.id &&
                                state.modelSelection?.providerId == provider.id,
                            onClick = { vm.selectModel(provider.id, model.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: ModelEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            OcStatusChip(text = stringResource(R.string.chat_model_selected), tone = OcChipTone.PRIMARY)
        }
    }
}
