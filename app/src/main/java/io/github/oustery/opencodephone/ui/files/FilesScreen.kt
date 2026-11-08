package io.github.oustery.opencodephone.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilesScreen(graph: AppGraph) {
    val vm: FilesViewModel = viewModel(factory = VmFactory.of { FilesViewModel(graph) })
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.tab_files),
            actions = {
                IconButton(onClick = { vm.showNewFile(true) }) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.files_new_file))
                }
                OutlinedButton(onClick = { vm.showClone(true) }) {
                    Text(stringResource(R.string.files_clone))
                }
            },
        )

        ScreenBody {
            state.gitBranch?.let { branch ->
                GitBar(
                    branch = branch,
                    changes = state.gitChanges,
                    onCommit = { vm.commit(it) },
                    onPush = { vm.push() },
                )
            }

            if (state.busy != null) {
                Column(modifier = Modifier.padding(16.dp)) {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(state.busy!!, style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.message?.let { message ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.dismissMessage() }) { Text(stringResource(R.string.action_dismiss)) }
                    }
                }
            }

            if (state.workspaceEmpty && state.busy == null) {
                OcEmptyState(
                    title = stringResource(R.string.files_empty_title),
                    description = stringResource(R.string.files_empty_body),
                    icon = painterResource(R.drawable.ic_tab_files),
                    modifier = Modifier.fillMaxSize(),
                    actionText = stringResource(R.string.files_clone),
                    onAction = { vm.showClone(true) },
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.file.absolutePath }) { entry ->
                        FileRow(entry, onClick = {
                            if (entry.isDir) vm.toggleDir(entry) else vm.openFile(entry)
                        })
                    }
                }
            }
        }
    }

    if (state.showCloneDialog) {
        CloneDialog(onConfirm = { vm.cloneRepository(it) }, onDismiss = { vm.showClone(false) })
    }
    if (state.showNewFileDialog) {
        NewFileDialog(onConfirm = { vm.createFile(it) }, onDismiss = { vm.showNewFile(false) })
    }
    state.editing?.let { editing ->
        EditorDialog(
            fileName = editing.file.name,
            content = editing.content,
            onContentChange = { vm.updateEditingContent(it) },
            onSave = { vm.saveEditing() },
            onDismiss = { vm.closeEditor() },
        )
    }
}

@Composable
private fun GitBar(branch: String, changes: Int, onCommit: (String) -> Unit, onPush: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OcStatusChip(text = branch, tone = OcChipTone.PRIMARY)
                OcStatusChip(
                    text = if (changes == 0) {
                        stringResource(R.string.git_clean)
                    } else {
                        stringResource(R.string.git_changes, changes)
                    },
                    tone = if (changes == 0) OcChipTone.NEUTRAL else OcChipTone.TERTIARY,
                )
            }
            var message by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    placeholder = { Text(stringResource(R.string.git_commit_hint)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
                Button(
                    onClick = {
                        onCommit(message)
                        message = ""
                    },
                    enabled = message.isNotBlank() && changes > 0,
                ) {
                    Text(stringResource(R.string.git_commit))
                }
                OutlinedButton(onClick = onPush) {
                    Text(stringResource(R.string.git_push))
                }
            }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (16 + entry.depth * 20).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (entry.isDir) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                painterResource(R.drawable.ic_tab_files),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            Spacer(Modifier.size(18.dp))
            Icon(
                painterResource(R.drawable.ic_file),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (entry.isDir) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CloneDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_clone_title)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("https://github.com/user/repo.git") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(url) }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.files_clone))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun NewFileDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_new_file)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.files_new_file_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun EditorDialog(
    fileName: String,
    content: String,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fileName) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier.fillMaxWidth().height(420.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                ),
            )
        },
        confirmButton = {
            Button(onClick = onSave) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
