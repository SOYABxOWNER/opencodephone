package io.github.oustery.opencodephone.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.R
import io.github.oustery.opencodephone.ScreenBody
import io.github.oustery.opencodephone.ScreenHeader
import io.github.oustery.opencodephone.core.designsystem.OcChipTone
import io.github.oustery.opencodephone.core.designsystem.OcInfoRow
import io.github.oustery.opencodephone.core.designsystem.OcSectionHeader
import io.github.oustery.opencodephone.core.designsystem.OcStatusChip
import io.github.oustery.opencodephone.core.settings.ServerMode
import io.github.oustery.opencodephone.ui.common.VmFactory

private val KNOWN_PROVIDERS = listOf(
    "anthropic", "openai", "google", "openrouter", "groq",
    "deepseek", "mistral", "xai", "azure", "amazon-bedrock", "github-copilot",
)

@Composable
fun SettingsScreen(graph: AppGraph) {
    val vm: SettingsViewModel = viewModel(factory = VmFactory.of { SettingsViewModel(graph) })
    val state by vm.state.collectAsStateWithLifecycle()
    val serverEnabled by graph.localServerEnabled.collectAsStateWithLifecycle()
    val runtimeState by graph.runtime.serverState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.tab_settings))
        ScreenBody {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                if (state.legacyPlaintextSettings) {
                    Text(stringResource(R.string.settings_legacy_plaintext), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { vm.importLegacySettings() }) { Text(stringResource(R.string.settings_import_legacy)) }
                }
                // ---- Server ------------------------------------------------
                OcSectionHeader(stringResource(R.string.settings_server_header))
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.server.mode == ServerMode.LOCAL,
                                onClick = { vm.setMode(ServerMode.LOCAL) },
                                label = { Text(stringResource(R.string.settings_server_local)) },
                            )
                            FilterChip(
                                selected = state.server.mode == ServerMode.REMOTE,
                                onClick = { vm.setMode(ServerMode.REMOTE) },
                                label = { Text(stringResource(R.string.settings_server_remote)) },
                            )
                        }
                        if (state.server.mode == ServerMode.REMOTE) {
                            Text(stringResource(R.string.settings_remote_security), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = state.server.host,
                                onValueChange = { vm.setHost(it) },
                                label = { Text(stringResource(R.string.settings_server_host)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.serverPortText,
                                onValueChange = { vm.setPort(it) },
                                label = { Text(stringResource(R.string.settings_server_port)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.small,
                            )
                            if (state.server.mode == ServerMode.REMOTE) {
                                OutlinedTextField(
                                    value = state.server.username,
                                    onValueChange = { vm.setUsername(it) },
                                    label = { Text(stringResource(R.string.settings_server_user)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.small,
                                )
                            }
                        }
                        if (state.server.mode == ServerMode.REMOTE) {
                            OutlinedTextField(
                                value = state.server.password,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                onValueChange = { vm.setPassword(it) },
                                label = { Text(stringResource(R.string.settings_server_password)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                            )
                        } else {
                            Text(stringResource(R.string.settings_local_auth), style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.server.mode == ServerMode.REMOTE) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(checked = state.providerSyncAllowed, onCheckedChange = vm::setProviderSyncAllowed)
                                Text(stringResource(R.string.settings_provider_consent), modifier = Modifier.weight(1f))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::applyServer, enabled = state.serverDirty) { Text(stringResource(R.string.action_apply)) }
                            OutlinedButton(onClick = vm::cancelServerChanges, enabled = state.serverDirty) { Text(stringResource(R.string.action_cancel)) }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.testConnection() }, enabled = !state.serverDirty) {
                                Text(stringResource(R.string.settings_server_test))
                            }
                            OcStatusChip(
                                text = state.serverStateLabel.ifEmpty { "—" },
                                tone = OcChipTone.NEUTRAL,
                            )
                        }
                        if (state.server.mode == ServerMode.LOCAL) {
                            Button(
                                onClick = {
                                    if (serverEnabled && (runtimeState is io.github.oustery.opencodephone.core.linuxenv.ServerState.Running ||
                                            runtimeState is io.github.oustery.opencodephone.core.linuxenv.ServerState.Starting)) graph.stopLocalServer()
                                    else graph.restartLocalServer()
                                },
                                enabled = !state.serverDirty && state.bootstrap.phase == io.github.oustery.opencodephone.core.model.BootstrapPhase.READY,
                            ) {
                                Text(stringResource(if (serverEnabled && (runtimeState is io.github.oustery.opencodephone.core.linuxenv.ServerState.Running ||
                                    runtimeState is io.github.oustery.opencodephone.core.linuxenv.ServerState.Starting)) R.string.settings_server_stop else R.string.settings_server_start))
                            }
                            io.github.oustery.opencodephone.core.designsystem.OcDisclosure(
                                title = stringResource(R.string.settings_server_help),
                                expandedDescription = stringResource(R.string.state_expanded),
                                collapsedDescription = stringResource(R.string.state_collapsed),
                            ) { Text(stringResource(R.string.settings_server_stop_note), style = MaterialTheme.typography.bodySmall) }

                            OutlinedButton(
                                onClick = { vm.restartServer() },
                                enabled = !state.serverDirty && runtimeState is io.github.oustery.opencodephone.core.linuxenv.ServerState.Running,
                            ) {
                                Text(stringResource(R.string.settings_server_restart))
                            }
                            state.serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (state.serverLog.isNotBlank()) {
                                Text(state.serverLog.takeLast(3000), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        state.connectionTest?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ---- AI providers -------------------------------------------
                OcSectionHeader(stringResource(R.string.settings_providers_header))
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.settings_providers_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.providers.forEach { key ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(key.providerId, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Text(
                                    "••••" + key.apiKey.takeLast(4),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { vm.removeProviderKey(key.providerId) }) {
                                    Text(stringResource(R.string.action_delete))
                                }
                            }
                        }
                        if (state.providers.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_providers_empty),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        OutlinedButton(onClick = { vm.showAddProvider(true) }) {
                            Text(stringResource(R.string.settings_providers_add))
                        }
                    }
                }

                // ---- Git ----------------------------------------------------
                OcSectionHeader(stringResource(R.string.settings_git_header))
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.git.userName,
                            onValueChange = { vm.setGitName(it) },
                            label = { Text(stringResource(R.string.settings_git_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                        )
                        OutlinedTextField(
                            value = state.git.userEmail,
                            onValueChange = { vm.setGitEmail(it) },
                            label = { Text(stringResource(R.string.settings_git_email)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                        )
                        OutlinedTextField(
                            value = state.git.githubToken,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            onValueChange = { vm.setGitToken(it) },
                            label = { Text(stringResource(R.string.settings_git_token)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                        )
                        Text(
                            stringResource(R.string.settings_git_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ---- Environment ---------------------------------------------
                OcSectionHeader(stringResource(R.string.settings_env_header))
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OcInfoRow(
                            label = stringResource(R.string.settings_env_bootstrap),
                            value = state.bootstrap.phase.name,
                        )
                        OcInfoRow(
                            label = stringResource(R.string.settings_env_workspace),
                            value = vm.workspacePath(),
                        )
                        OutlinedTextField(
                            value = state.opencodePin,
                            onValueChange = { vm.setOpencodePin(it) },
                            label = { Text(stringResource(R.string.settings_env_pin)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                        )
                        Text(
                            stringResource(R.string.settings_env_pin_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { vm.showReinstallConfirm(true) }) {
                            Text(stringResource(R.string.settings_env_reinstall))
                        }
                    }
                }

                // ---- About ---------------------------------------------------
                OcSectionHeader(stringResource(R.string.settings_about_header))
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OcInfoRow(stringResource(R.string.settings_about_app), stringResource(R.string.app_name))
                        OcInfoRow(stringResource(R.string.settings_about_version), io.github.oustery.opencodephone.BuildConfig.VERSION_NAME)
                        OcInfoRow(stringResource(R.string.settings_about_license), "MIT")
                        OcInfoRow(
                            stringResource(R.string.settings_about_repo),
                            "github.com/oustery/opencodephone",
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_about_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                io.github.oustery.opencodephone.ui.update.AppUpdateCard()
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (state.showAddProvider) {
        AddProviderDialog(
            known = KNOWN_PROVIDERS,
            onSave = { id, key -> vm.saveProviderKey(id, key) },
            onDismiss = { vm.showAddProvider(false) },
        )
    }
    if (state.showReinstallConfirm) {
        AlertDialog(
            onDismissRequest = { vm.showReinstallConfirm(false) },
            title = { Text(stringResource(R.string.settings_env_reinstall)) },
            text = { Text(stringResource(R.string.settings_env_reinstall_confirm)) },
            confirmButton = {
                Button(onClick = { vm.reinstallEnvironment() }) {
                    Text(stringResource(R.string.settings_env_reinstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.showReinstallConfirm(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AddProviderDialog(
    known: List<String>,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var providerId by remember { mutableStateOf(known.first()) }
    var custom by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_providers_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!custom) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        known.take(8).forEach { id ->
                            Text(
                                text = id,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        providerId = id
                                        custom = true
                                    }
                                    .padding(vertical = 10.dp),
                            )
                        }
                        Text(
                            stringResource(R.string.settings_providers_custom),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { custom = true }.padding(vertical = 10.dp),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = providerId,
                        onValueChange = { providerId = it },
                        label = { Text(stringResource(R.string.settings_providers_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    )
                }
                OutlinedTextField(
                    value = apiKey,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.settings_providers_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(providerId, apiKey) },
                enabled = providerId.isNotBlank() && apiKey.isNotBlank() && custom,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
