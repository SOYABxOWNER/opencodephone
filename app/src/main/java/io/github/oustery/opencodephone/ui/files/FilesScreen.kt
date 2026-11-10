package io.github.oustery.opencodephone.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.oustery.opencodephone.core.designsystem.AppFonts
import io.github.oustery.opencodephone.core.designsystem.CodeTypography
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.R
import io.github.oustery.opencodephone.ScreenBody
import io.github.oustery.opencodephone.ScreenHeader
import io.github.oustery.opencodephone.core.designsystem.OcChipTone
import io.github.oustery.opencodephone.core.designsystem.OcEmptyState
import io.github.oustery.opencodephone.core.designsystem.OcSkeletonRows
import io.github.oustery.opencodephone.core.designsystem.OcStatusChip
import io.github.oustery.opencodephone.ui.common.VmFactory

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilesScreen(graph: AppGraph) {
    val vm: FilesViewModel = viewModel(factory = VmFactory.of { FilesViewModel(graph) })
    val state by vm.state.collectAsStateWithLifecycle()
    // Per-row file actions (functional audit F-007).
    var actionsFor by remember { mutableStateOf<FileEntry?>(null) }
    var renameFor by remember { mutableStateOf<FileEntry?>(null) }
    var deleteFor by remember { mutableStateOf<FileEntry?>(null) }
    // Artifact preview (functional audit F-017).
    var previewFor by remember { mutableStateOf<FileEntry?>(null) }
    val context = LocalContext.current

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
            state.gitProject?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
            state.gitBranch?.let { branch ->
                GitBar(
                    branch = branch,
                    changes = state.gitChanges,
                    onOpenCommit = { vm.showCommit(true) },
                    onPull = { vm.pull() },
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
            } else if (state.entries.isEmpty() && state.busy == null) {
                // The first scan has not reported yet (workspaceEmpty is only
                // decided after it completes): placeholder rows instead of a
                // blank screen (M3E audit E-017).
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.files_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OcSkeletonRows(rows = 6)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.file.absolutePath }) { entry ->
                        FileRow(
                            entry,
                            expanded = entry.file.absolutePath in state.expanded,
                            onClick = { if (entry.isDir) vm.toggleDir(entry) else vm.openFile(entry) },
                            onLongClick = { actionsFor = entry },
                        )
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
    if (state.showCommitDialog) {
        // Selective staging instead of a blind `git add -A` (functional audit
        // F-008): the user picks which changed files enter the commit.
        CommitDialog(
            changes = state.changeList,
            onConfirm = { message, selected -> vm.commit(message, selected) },
            onDismiss = { vm.showCommit(false) },
        )
    }
    actionsFor?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(entry.name) },
            text = { Text(stringResource(if (entry.isDir) R.string.files_actions_dir_hint else R.string.files_actions_file_hint)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!entry.isDir && isPreviewable(entry.name)) {
                        TextButton(onClick = {
                            actionsFor = null
                            previewFor = entry
                        }) { Text(stringResource(R.string.files_preview)) }
                    }
                    if (!entry.isDir) {
                        TextButton(onClick = {
                            actionsFor = null
                            shareWorkspaceFile(context, entry.file)
                        }) { Text(stringResource(R.string.files_share)) }
                    }
                    TextButton(onClick = {
                        actionsFor = null
                        renameFor = entry
                    }) { Text(stringResource(R.string.files_rename)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    actionsFor = null
                    deleteFor = entry
                }) { Text(stringResource(R.string.action_delete)) }
            },
        )
    }
    renameFor?.let { entry ->
        RenameFileDialog(
            initial = entry.name,
            onConfirm = {
                vm.renameEntry(entry, it)
                renameFor = null
            },
            onDismiss = { renameFor = null },
        )
    }
    deleteFor?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.files_delete_title, entry.name)) },
            text = {
                Text(
                    stringResource(
                        if (entry.isDir) R.string.files_delete_dir_body else R.string.files_delete_file_body,
                    ),
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.deleteEntry(entry)
                    deleteFor = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    state.editing?.let { editing ->
        // Unsaved-changes guard (functional audit F-009): back/outside/side
        // dismissal of a dirty editor asks instead of silently discarding.
        var confirmDiscard by rememberSaveable { mutableStateOf(false) }
        EditorDialog(
            fileName = editing.file.name,
            content = editing.content,
            onContentChange = { vm.updateEditingContent(it) },
            onSave = { vm.saveEditing() },
            onDismiss = {
                if (editing.content != editing.snapshot.content) confirmDiscard = true else vm.closeEditor()
            },
        )
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text(stringResource(R.string.editor_discard_title)) },
                text = { Text(stringResource(R.string.editor_discard_body, editing.file.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDiscard = false
                        vm.closeEditor()
                    }) { Text(stringResource(R.string.editor_discard_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
    previewFor?.let { entry ->
        PreviewDialog(entry, onDismiss = { previewFor = null })
    }
}

@Composable
private fun GitBar(branch: String, changes: Int, onOpenCommit: () -> Unit, onPull: () -> Unit, onPush: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenCommit, enabled = changes > 0) {
                    Text(stringResource(R.string.git_commit))
                }
                OutlinedButton(onClick = onPull) {
                    Text(stringResource(R.string.git_pull))
                }
                OutlinedButton(onClick = onPush) {
                    Text(stringResource(R.string.git_push))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: FileEntry, expanded: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val description = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    val actionHint = stringResource(R.string.files_row_actions)
    Row(
        modifier = Modifier
            .fillMaxWidth().heightIn(min = 48.dp)
            .semantics { if (entry.isDir) stateDescription = description }
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = actionHint,
            )
            .padding(start = (16 + entry.depth.coerceAtMost(6) * 20).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
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
            modifier = Modifier.weight(1f),
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

/** Selective commit: message plus which changed files to stage (F-008). */
@Composable
private fun CommitDialog(
    changes: List<GitChange>,
    onConfirm: (String, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(changes.map { it.path }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_commit_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    placeholder = { Text(stringResource(R.string.git_commit_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                Text(
                    stringResource(R.string.git_commit_stage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(changes, key = { "${it.status}:${it.path}" }) { change ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = change.path in selected,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + change.path else selected - change.path
                                },
                            )
                            Text(
                                change.status,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = AppFonts.Code,
                                modifier = Modifier.width(28.dp),
                            )
                            Text(
                                change.path,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(message, selected) },
                enabled = message.isNotBlank() && selected.isNotEmpty(),
            ) {
                Text(stringResource(R.string.git_commit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RenameFileDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.files_rename_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name.trim() != initial,
            ) {
                Text(stringResource(R.string.action_save))
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            val compact = maxHeight < 520.dp || maxWidth < 600.dp
            Surface(
                modifier = if (compact) Modifier.fillMaxSize() else Modifier.widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 720.dp),
                shape = if (compact) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    OutlinedTextField(
                        value = content, onValueChange = onContentChange,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        textStyle = CodeTypography.editor,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                        Button(onClick = onSave) { Text(stringResource(R.string.action_save)) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------- Preview

private sealed interface PreviewState {
    data object Loading : PreviewState
    data class Image(val bitmap: android.graphics.Bitmap) : PreviewState
    data class Html(val html: String) : PreviewState
    data class Failed(val reason: String) : PreviewState
}

private val PREVIEW_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
private const val PREVIEW_MAX_BYTES = 10 * 1024 * 1024

/** Only files the preview dialog can actually render (functional audit F-017). */
private fun isPreviewable(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in PREVIEW_IMAGE_EXTENSIONS || ext == "html" || ext == "htm"
}

/**
 * Read the file on the IO dispatcher with a hard size cap; images are
 * decoded through BitmapFactory, HTML is returned as text (rendered with
 * JavaScript disabled).
 */
private fun loadPreview(file: java.io.File): PreviewState {
    val ext = file.extension.lowercase()
    return try {
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > PREVIEW_MAX_BYTES) return@use null
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } ?: return PreviewState.Failed("File is larger than 10 MB")
        when (ext) {
            in PREVIEW_IMAGE_EXTENSIONS -> {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return PreviewState.Failed("Cannot decode the image")
                PreviewState.Image(bitmap)
            }
            "html", "htm" -> PreviewState.Html(bytes.toString(Charsets.UTF_8))
            else -> PreviewState.Failed("No preview for this file type")
        }
    } catch (e: Exception) {
        PreviewState.Failed(e.message ?: "Preview failed")
    }
}

/**
 * Fullscreen preview of agent artifacts (functional audit F-017): images and
 * HTML pages the agent generated can finally be looked at inside the app.
 * JavaScript stays disabled — the preview renders static content only.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewDialog(entry: FileEntry, onDismiss: () -> Unit) {
    val state = remember(entry.file.absolutePath) { mutableStateOf<PreviewState>(PreviewState.Loading) }
    LaunchedEffect(entry.file.absolutePath) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            state.value = loadPreview(entry.file)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            val compact = maxHeight < 520.dp || maxWidth < 600.dp
            Surface(
                modifier = if (compact) Modifier.fillMaxSize() else Modifier.widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 720.dp),
                shape = if (compact) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    when (val s = state.value) {
                        PreviewState.Loading -> Column(
                            modifier = Modifier.fillMaxSize().weight(1f),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LinearWavyProgressIndicator()
                        }
                        is PreviewState.Image -> Image(
                            bitmap = s.bitmap.asImageBitmap(),
                            contentDescription = entry.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().weight(1f),
                        )
                        is PreviewState.Html -> AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    settings.javaScriptEnabled = false
                                    loadDataWithBaseURL(null, s.html, "text/html", "utf-8", null)
                                }
                            },
                            modifier = Modifier.fillMaxSize().weight(1f),
                            update = { view ->
                                if (view.settings.javaScriptEnabled) view.settings.javaScriptEnabled = false
                            },
                        )
                        is PreviewState.Failed -> Column(
                            modifier = Modifier.fillMaxSize().weight(1f),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(R.string.files_preview_failed, s.reason),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }
    }
}

/** Share a workspace file with any installed app via the FileProvider (F-016). */
private fun shareWorkspaceFile(context: android.content.Context, file: java.io.File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        context.packageName + ".updates",
        file,
    )
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, null))
}
