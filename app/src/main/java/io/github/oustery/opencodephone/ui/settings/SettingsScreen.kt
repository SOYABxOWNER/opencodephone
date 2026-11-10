package io.github.oustery.opencodephone.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.R
import io.github.oustery.opencodephone.ScreenBody
import io.github.oustery.opencodephone.core.designsystem.OcChipTone
import io.github.oustery.opencodephone.core.designsystem.AppFonts
import io.github.oustery.opencodephone.core.designsystem.OcInfoRow
import io.github.oustery.opencodephone.core.designsystem.OcSectionHeader
import io.github.oustery.opencodephone.core.designsystem.OcStatusChip
import io.github.oustery.opencodephone.core.model.ProviderAuthPrompt
import io.github.oustery.opencodephone.core.settings.ServerMode
import io.github.oustery.opencodephone.ui.common.VmFactory

private val KNOWN_PROVIDERS = listOf(
    "anthropic", "openai", "google", "openrouter", "groq",
    "deepseek", "mistral", "xai", "azure", "amazon-bedrock", "github-copilot",
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun SettingsScreen(graph: AppGraph) {
    val vm: SettingsViewModel = viewModel(factory = VmFactory.of { SettingsViewModel(graph) })
    val state by vm.state.collectAsStateWithLifecycle()
    val serverEnabled by graph.localServerEnabled.collectAsStateWithLifecycle()
    val runtimeState by graph.runtime.serverState.collectAsStateWithLifecycle()
    // Mode-switch confirmation (M3E audit E-010).
    val haptics = LocalHapticFeedback.current
    // Expressive collapsing header (M3E audit E-019): the flagship
    // LargeFlexibleTopAppBar shrinks to a compact bar as the settings body
    // scrolls. Settings is the longest screen, so it carries the pattern;
    // dense screens (chat/files/terminal) keep the compact ScreenHeader.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Settings backup (functional audit F-013) via the storage Access Framework.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportSettings(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onImportPicked(uri)
    }
    var confirmClear by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        LargeFlexibleTopAppBar(
            title = { Text(stringResource(R.string.tab_settings)) },
            subtitle = { Text(stringResource(R.string.settings_subtitle)) },
            scrollBehavior = scrollBehavior,
        )
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
                        // Mutually exclusive modes read as one segmented control,
                        // not two independent filter chips (M3E audit E-006).
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = state.server.mode == ServerMode.LOCAL,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.setMode(ServerMode.LOCAL)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            ) {
                                Text(stringResource(R.string.settings_server_local))
                            }
                            SegmentedButton(
                                selected = state.server.mode == ServerMode.REMOTE,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.setMode(ServerMode.REMOTE)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            ) {
                                Text(stringResource(R.string.settings_server_remote))
                            }
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

                // ---- OpenCode account -------------------------------------
                OcSectionHeader(stringResource(R.string.settings_account_header))
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val account = state.account
                        if (account != null) {
                            Text(
                                stringResource(R.string.settings_account_signed_in, account.email),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            account.orgName?.let {
                                Text(
                                    stringResource(R.string.settings_account_org, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                stringResource(R.string.settings_account_key_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { vm.signOutAccount() }) {
                                Text(stringResource(R.string.settings_account_sign_out))
                            }
                        } else {
                            Text(
                                stringResource(R.string.settings_account_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { vm.startConsoleSignIn() }) {
                                Text(stringResource(R.string.settings_account_sign_in))
                            }
                        }
                        state.accountMessage?.let {
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
                        // Interactive sign-in methods reported by the server
                        // (v1.19.5 auth plugins: github-copilot, ChatGPT, xai…).
                        if (state.serverAuthMethods.isNotEmpty()) {
                            Text(
                                stringResource(R.string.settings_providers_oauth_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.serverAuthMethods.keys.forEach { providerId ->
                                    OutlinedButton(onClick = { vm.startProviderOAuth(providerId) }) {
                                        Text(stringResource(R.string.settings_providers_oauth_sign_in, providerId))
                                    }
                                }
                            }
                        }
                        state.providers.forEach { key ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(key.providerId, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Text(
                                    "••••" + key.apiKey.takeLast(4),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = AppFonts.Code,
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
                        state.providerMessage?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                        if (state.bootstrap.phase != io.github.oustery.opencodephone.core.model.BootstrapPhase.READY) {
                            // Remote-first path (F-022): reachable only when the
                            // user skipped the local install; routing locks main
                            // navigation until a choice exists otherwise.
                            Text(
                                stringResource(R.string.settings_env_install_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { vm.installLocalEnvironment() }) {
                                Text(stringResource(R.string.settings_env_install))
                            }
                        }
                    }
                }

                // ---- Appearance ----------------------------------------------
                OcSectionHeader(stringResource(R.string.settings_appearance_header))
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_appearance_dynamic), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.settings_appearance_dynamic_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.dynamicColor,
                                onCheckedChange = { enabled ->
                                    // Confirmation haptic, same pattern as mode switch (E-010).
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.setDynamicColor(enabled)
                                },
                            )
                        }
                    }
                }

                // ---- Backup & data (functional audit F-013) -----------------
                OcSectionHeader(stringResource(R.string.settings_backup_header))
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.settings_backup_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { exportLauncher.launch("opencodephone-settings.json") }) {
                                Text(stringResource(R.string.settings_backup_export))
                            }
                            OutlinedButton(onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }) {
                                Text(stringResource(R.string.settings_backup_import))
                            }
                        }
                        state.dataMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                OcSectionHeader(stringResource(R.string.settings_data_header))
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.settings_data_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { confirmClear = "drafts" }) {
                            Text(stringResource(R.string.settings_data_item_drafts))
                        }
                        OutlinedButton(onClick = { confirmClear = "uploads" }) {
                            Text(stringResource(R.string.settings_data_item_uploads))
                        }
                        OutlinedButton(onClick = { confirmClear = "cache" }) {
                            Text(stringResource(R.string.settings_data_item_cache))
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
    state.accountAuth?.let { auth ->
        when (auth) {
            is AccountAuthUi.AwaitApproval -> ConsoleSignInDialog(
                userCode = auth.userCode,
                verificationUrl = auth.verificationUrl,
                onOpen = { vm.openVerificationUrl(auth.verificationUrl) },
                onCancel = { vm.dismissAccountAuth() },
            )
            is AccountAuthUi.Failed -> AlertDialog(
                onDismissRequest = { vm.dismissAccountAuth() },
                title = { Text(stringResource(R.string.settings_account_failed_title)) },
                text = { Text(auth.message) },
                confirmButton = {
                    Button(onClick = {
                        vm.dismissAccountAuth()
                        vm.startConsoleSignIn()
                    }) { Text(stringResource(R.string.settings_account_retry)) }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissAccountAuth() }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }
    state.providerOAuth?.let { oauth ->
        when (oauth) {
            is ProviderOAuthUi.CollectInputs -> ProviderOAuthInputsDialog(
                prompts = oauth.prompts,
                title = oauth.label.ifBlank { oauth.providerId },
                onSubmit = { vm.submitProviderOAuthInputs(it) },
                onDismiss = { vm.dismissProviderOAuth() },
            )
            is ProviderOAuthUi.AwaitBrowser -> ProviderOAuthBrowserDialog(
                completion = oauth.completion,
                instructions = oauth.instructions,
                onOpen = { vm.openProviderOAuthUrl() },
                onCode = { vm.submitProviderOAuthCode(it) },
                onDismiss = { vm.dismissProviderOAuth() },
            )
            is ProviderOAuthUi.Failed -> AlertDialog(
                onDismissRequest = { vm.dismissProviderOAuth() },
                title = { Text(stringResource(R.string.settings_account_failed_title)) },
                text = { Text(oauth.message) },
                confirmButton = {
                    Button(onClick = {
                        vm.dismissProviderOAuth()
                        vm.startProviderOAuth(oauth.providerId)
                    }) { Text(stringResource(R.string.settings_account_retry)) }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissProviderOAuth() }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }
    if (state.showImportConfirm) {
        AlertDialog(
            onDismissRequest = { vm.dismissImportConfirm() },
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_import_confirm_body)) },
            confirmButton = {
                Button(onClick = { vm.confirmImport() }) {
                    Text(stringResource(R.string.settings_backup_import))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissImportConfirm() }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    confirmClear?.let { kind ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text(stringResource(R.string.settings_data_clear_title)) },
            text = {
                Text(
                    stringResource(
                        when (kind) {
                            "drafts" -> R.string.settings_data_confirm_drafts
                            "uploads" -> R.string.settings_data_confirm_uploads
                            else -> R.string.settings_data_confirm_cache
                        },
                    ),
                )
            },
            confirmButton = {
                Button(onClick = {
                    when (kind) {
                        "drafts" -> vm.clearDrafts()
                        "uploads" -> vm.clearUploads()
                        else -> vm.clearInstallerCache()
                    }
                    confirmClear = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = null }) { Text(stringResource(R.string.action_cancel)) }
            },
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

/**
 * Collect the declared auth-method inputs (v1.19.5 `ProviderAuth.Prompt`):
 * text fields for `text` prompts and single-select chips for `select` ones.
 */
@Composable
private fun ProviderOAuthInputsDialog(
    prompts: List<ProviderAuthPrompt>,
    title: String,
    onSubmit: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember(prompts) { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_providers_oauth_inputs_title, title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                prompts.forEach { prompt ->
                    when (prompt.type) {
                        "select" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(prompt.message, style = MaterialTheme.typography.bodyMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    prompt.options.forEach { option ->
                                        FilterChip(
                                            selected = values[prompt.key] == option.value,
                                            onClick = { values[prompt.key] = option.value },
                                            label = { Text(option.label) },
                                        )
                                    }
                                }
                            }
                        }
                        else -> OutlinedTextField(
                            value = values[prompt.key].orEmpty(),
                            onValueChange = { values[prompt.key] = it },
                            label = { Text(prompt.message) },
                            placeholder = prompt.placeholder?.let { hint -> { Text(hint) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(values.toMap()) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Browser step of a provider OAuth flow. `code` flows wait for the pasted
 * authorization code; `auto` flows show a spinner while the VM polls the
 * server-side connected list.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProviderOAuthBrowserDialog(
    completion: String,
    instructions: String,
    onOpen: () -> Unit,
    onCode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val needsCode = completion == "code"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_providers_oauth_browser_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (instructions.isNotBlank()) {
                    Text(instructions, style = MaterialTheme.typography.bodyMedium)
                }
                FilledTonalButton(onClick = onOpen) {
                    Text(stringResource(R.string.settings_account_open_browser))
                }
                if (needsCode) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.settings_providers_oauth_code_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearWavyProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.settings_account_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (needsCode) {
                TextButton(onClick = { onCode(code) }, enabled = code.isNotBlank()) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Device-flow sign-in dialog: shows the short confirmation code and opens
 * the console verification URL in the system browser. The ViewModel keeps
 * polling the token endpoint in the background while the dialog is up.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConsoleSignInDialog(
    userCode: String,
    verificationUrl: String,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_account_code_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.settings_account_code_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    userCode,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = AppFonts.Code,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(userCode)) }) {
                        Text(stringResource(R.string.action_copy))
                    }
                    FilledTonalButton(onClick = onOpen) {
                        Text(stringResource(R.string.settings_account_open_browser))
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearWavyProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.settings_account_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
                        // Every known provider as a selectable chip — no truncation
                        // (M3E audit E-007: take(8) made azure/amazon-bedrock/
                        // github-copilot unreachable from the list).
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            known.forEach { id ->
                                FilterChip(
                                    selected = providerId == id,
                                    onClick = {
                                        providerId = id
                                        custom = true
                                    },
                                    label = { Text(id) },
                                )
                            }
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
