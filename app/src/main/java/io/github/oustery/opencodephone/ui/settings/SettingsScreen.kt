package io.github.oustery.opencodephone.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oustery.opencodephone.AppGraph
import io.github.oustery.opencodephone.BuildConfig
import io.github.oustery.opencodephone.NavMenuButton
import io.github.oustery.opencodephone.R
import io.github.oustery.opencodephone.ScreenBody
import io.github.oustery.opencodephone.core.designsystem.AppFonts
import io.github.oustery.opencodephone.core.designsystem.CodeTypography
import io.github.oustery.opencodephone.core.designsystem.OcChipTone
import io.github.oustery.opencodephone.core.designsystem.OcDisclosure
import io.github.oustery.opencodephone.core.designsystem.OcEmptyState
import io.github.oustery.opencodephone.core.designsystem.OcGroupCard
import io.github.oustery.opencodephone.core.designsystem.OcInfoRow
import io.github.oustery.opencodephone.core.designsystem.OcPageScaffold
import io.github.oustery.opencodephone.core.designsystem.OcSectionHeader
import io.github.oustery.opencodephone.core.designsystem.OcStatusChip
import io.github.oustery.opencodephone.core.designsystem.OcToggleableRow
import io.github.oustery.opencodephone.core.designsystem.OcTwoPane
import io.github.oustery.opencodephone.core.linuxenv.ServerState
import io.github.oustery.opencodephone.core.model.BootstrapPhase
import io.github.oustery.opencodephone.core.model.ProviderAuthPrompt
import io.github.oustery.opencodephone.core.settings.ServerMode
import io.github.oustery.opencodephone.ui.common.VmFactory
import io.github.oustery.opencodephone.ui.update.AppUpdateCard

private val KNOWN_PROVIDERS = listOf(
    "anthropic", "openai", "google", "openrouter", "groq",
    "deepseek", "mistral", "xai", "azure", "amazon-bedrock", "github-copilot",
)

/**
 * Groups of the redesigned settings screen (Android-Settings pattern): a
 * short root list of [OcGroupCard]s, each opening one focused sub-page.
 * Every page shares the single [SettingsViewModel], so state, dirty flags
 * and dialogs behave exactly as before — only the information architecture
 * changed, not the wiring.
 */
private enum class SettingsPage { SERVER, PROVIDERS, GIT, ENVIRONMENT, APP }

@Composable
fun SettingsScreen(graph: AppGraph) {
    val vm: SettingsViewModel = viewModel(factory = VmFactory.of { SettingsViewModel(graph) })
    val state by vm.state.collectAsStateWithLifecycle()

    // Selected group, saved as the enum name so it survives rotation and
    // process death. `BackHandler` makes system back leave the sub-page
    // before leaving the screen (the old flat page had no hierarchy).
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedPage = selectedName?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
    val goBack: () -> Unit = { selectedName = null }
    BackHandler(enabled = selectedPage != null, onBack = goBack)

    // Settings backup (functional audit F-013) via the storage Access
    // Framework. Launchers live at the screen root: both panes need them.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportSettings(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onImportPicked(uri)
    }

    OcTwoPane(
        showDetail = selectedPage != null,
        listPane = {
            SettingsGroupList(
                state = state,
                selected = selectedPage,
                onSelect = { page -> selectedName = page.name },
            )
        },
        detailPane = {
            Crossfade(targetState = selectedPage, label = "settings-page") { page ->
                when (page) {
                    SettingsPage.SERVER -> ServerPage(graph, vm, state, goBack)
                    SettingsPage.PROVIDERS -> ProvidersPage(vm, state, goBack)
                    SettingsPage.GIT -> GitPage(vm, state, goBack)
                    SettingsPage.ENVIRONMENT -> EnvironmentPage(vm, state, goBack)
                    SettingsPage.APP -> AppPage(graph, vm, state, exportLauncher, importLauncher, goBack)
                    null -> SettingsDetailPlaceholder()
                }
            }
        },
    )
}

@Composable
private fun SettingsGroupList(
    state: SettingsUiState,
    selected: SettingsPage?,
    onSelect: (SettingsPage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // A plain header instead of a collapsing app bar: the list is short,
        // and the drawer menu keeps its permanent slot next to the title.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NavMenuButton()
            Text(
                text = stringResource(R.string.tab_settings),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsPage.entries.forEach { page ->
                OcGroupCard(
                    title = pageTitle(page),
                    summary = groupSummary(state, page),
                    icon = painterResource(groupIcon(page)),
                    onClick = { onSelect(page) },
                    selected = page == selected,
                )
            }
        }
    }
}

@Composable
private fun pageTitle(page: SettingsPage): String = stringResource(
    when (page) {
        SettingsPage.SERVER -> R.string.settings_server_header
        SettingsPage.PROVIDERS -> R.string.settings_group_providers_title
        SettingsPage.GIT -> R.string.settings_git_header
        SettingsPage.ENVIRONMENT -> R.string.settings_env_header
        SettingsPage.APP -> R.string.settings_group_app_title
    },
)

/** Live one-line preview under each group title (the practical bit). */
@Composable
private fun groupSummary(state: SettingsUiState, page: SettingsPage): String = when (page) {
    SettingsPage.SERVER -> state.serverStateLabel.ifBlank {
        val mode = stringResource(
            if (state.server.mode == ServerMode.LOCAL) R.string.settings_server_local else R.string.settings_server_remote,
        )
        if (state.server.mode == ServerMode.LOCAL) "$mode · ${state.serverPortText}" else mode
    }
    SettingsPage.PROVIDERS -> state.account?.email
        ?: if (state.providers.isNotEmpty()) {
            stringResource(R.string.settings_group_keys_count, state.providers.size)
        } else {
            stringResource(R.string.settings_providers_empty)
        }
    SettingsPage.GIT -> state.git.userEmail.ifBlank { stringResource(R.string.settings_group_git_summary_empty) }
    SettingsPage.ENVIRONMENT -> stringResource(R.string.settings_env_bootstrap) + " · " +
        state.bootstrap.phase.name.lowercase()
    SettingsPage.APP -> BuildConfig.VERSION_NAME
}

private fun groupIcon(page: SettingsPage): Int = when (page) {
    SettingsPage.SERVER -> R.drawable.ic_tab_terminal
    SettingsPage.PROVIDERS -> R.drawable.ic_stat_prompt
    SettingsPage.GIT -> R.drawable.ic_diff
    SettingsPage.ENVIRONMENT -> R.drawable.ic_tab_files
    SettingsPage.APP -> R.drawable.ic_tab_settings
}

/** Wide two-pane placeholder while no group is selected. */
@Composable
private fun SettingsDetailPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
    ) {
        OcEmptyState(
            title = stringResource(R.string.tab_settings),
            description = stringResource(R.string.settings_pick_section),
        )
    }
}

// ------------------------------------------------------------------ Server

@Composable
private fun ServerPage(graph: AppGraph, vm: SettingsViewModel, state: SettingsUiState, onBack: () -> Unit) {
    val serverEnabled by graph.localServerEnabled.collectAsStateWithLifecycle()
    val runtimeState by graph.runtime.serverState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    OcPageScaffold(
        title = stringResource(R.string.settings_server_header),
        backContentDescription = stringResource(R.string.nav_back),
        onBack = onBack,
    ) {
        ScreenBody {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                                // Digits-only IME (UI audit A5); non-digits are
                                // dropped in the view model.
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                            // Whole row toggles; the checkbox itself is inert so
                            // tapping the label works too (UI audit A4).
                            OcToggleableRow(
                                checked = state.providerSyncAllowed,
                                onCheckedChange = vm::setProviderSyncAllowed,
                                role = Role.Checkbox,
                            ) {
                                Checkbox(checked = state.providerSyncAllowed, onCheckedChange = null)
                                Text(stringResource(R.string.settings_provider_consent), modifier = Modifier.weight(1f))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::applyServer, enabled = state.serverDirty) { Text(stringResource(R.string.action_apply)) }
                            OutlinedButton(onClick = vm::cancelServerChanges, enabled = state.serverDirty) { Text(stringResource(R.string.action_cancel)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { vm.testConnection() },
                                enabled = !state.serverDirty && !state.connectionTesting,
                            ) {
                                // Inline progress while the health check runs
                                // (UI audit B4); the button locks for the test.
                                if (state.connectionTesting) {
                                    CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.settings_server_test))
                            }
                            OcStatusChip(
                                text = state.serverStateLabel.ifEmpty { "—" },
                                tone = when (state.serverState) {
                                    // Chip tone follows the real server state
                                    // (UI audit B4) instead of a permanent
                                    // neutral label.
                                    is ServerState.Running -> OcChipTone.PRIMARY
                                    is ServerState.Failed -> OcChipTone.ERROR
                                    else -> OcChipTone.NEUTRAL
                                },
                            )
                        }
                        state.connectionTest?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.server.mode == ServerMode.LOCAL) {
                            Button(
                                onClick = {
                                    if (serverEnabled && (runtimeState is ServerState.Running ||
                                            runtimeState is ServerState.Starting)
                                    ) graph.stopLocalServer()
                                    else graph.restartLocalServer()
                                },
                                enabled = !state.serverDirty && state.bootstrap.phase == BootstrapPhase.READY,
                            ) {
                                Text(
                                    stringResource(
                                        if (serverEnabled && (runtimeState is ServerState.Running ||
                                                runtimeState is ServerState.Starting)
                                        ) R.string.settings_server_stop else R.string.settings_server_start,
                                    ),
                                )
                            }
                            OcDisclosure(
                                title = stringResource(R.string.settings_server_help),
                                expandedDescription = stringResource(R.string.state_expanded),
                                collapsedDescription = stringResource(R.string.state_collapsed),
                            ) {
                                Text(stringResource(R.string.settings_server_stop_note), style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                onClick = { vm.restartServer() },
                                enabled = !state.serverDirty && runtimeState is ServerState.Running,
                            ) {
                                Text(stringResource(R.string.settings_server_restart))
                            }
                            state.serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (state.serverLog.isNotBlank()) {
                                // Terminal log in the bundled mono face, capped
                                // in height so it cannot push the page apart
                                // (UI audit C5).
                                Text(
                                    state.serverLog.takeLast(3000),
                                    style = CodeTypography.output,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.heightIn(max = 220.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ------------------------------------------------------- Account & providers

@Composable
private fun ProvidersPage(vm: SettingsViewModel, state: SettingsUiState, onBack: () -> Unit) {
    // Provider-key removal asks for confirmation first (UI audit B2).
    var pendingKeyRemoval by remember { mutableStateOf<String?>(null) }

    OcPageScaffold(
        title = stringResource(R.string.settings_group_providers_title),
        backContentDescription = stringResource(R.string.nav_back),
        onBack = onBack,
    ) {
        ScreenBody {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                                // Deleting credentials is destructive: confirm
                                // first and paint the action in the error tone
                                // (UI audit B2).
                                TextButton(onClick = { pendingKeyRemoval = key.providerId }) {
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
                Spacer(Modifier.height(16.dp))
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
    pendingKeyRemoval?.let { providerId ->
        // Destructive action confirmation (UI audit B2): erasing stored
        // credentials must never be a single tap.
        AlertDialog(
            onDismissRequest = { pendingKeyRemoval = null },
            title = { Text(stringResource(R.string.settings_providers_remove_title)) },
            text = { Text(stringResource(R.string.settings_providers_remove_warning, providerId)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingKeyRemoval = null
                        vm.removeProviderKey(providerId)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingKeyRemoval = null }) { Text(stringResource(R.string.action_cancel)) }
            },
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
}

// ---------------------------------------------------------------------- Git

@Composable
private fun GitPage(vm: SettingsViewModel, state: SettingsUiState, onBack: () -> Unit) {
    OcPageScaffold(
        title = stringResource(R.string.settings_git_header),
        backContentDescription = stringResource(R.string.nav_back),
        onBack = onBack,
    ) {
        ScreenBody {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// -------------------------------------------------------------- Environment

@Composable
private fun EnvironmentPage(vm: SettingsViewModel, state: SettingsUiState, onBack: () -> Unit) {
    OcPageScaffold(
        title = stringResource(R.string.settings_env_header),
        backContentDescription = stringResource(R.string.nav_back),
        onBack = onBack,
    ) {
        ScreenBody {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                        if (state.bootstrap.phase != BootstrapPhase.READY) {
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
                Spacer(Modifier.height(16.dp))
            }
        }
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

// --------------------------------------------------------------- App & data

@Composable
private fun AppPage(
    graph: AppGraph,
    vm: SettingsViewModel,
    state: SettingsUiState,
    exportLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, android.net.Uri?>,
    importLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, android.net.Uri?>,
    onBack: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // Data clearing asks for confirmation first (destructive, UI audit B2).
    var confirmClear by remember { mutableStateOf<String?>(null) }

    OcPageScaffold(
        title = stringResource(R.string.settings_group_app_title),
        backContentDescription = stringResource(R.string.nav_back),
        onBack = onBack,
    ) {
        ScreenBody {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.legacyPlaintextSettings) {
                    Text(stringResource(R.string.settings_legacy_plaintext), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { vm.importLegacySettings() }) { Text(stringResource(R.string.settings_import_legacy)) }
                }

                // ---- Appearance ----------------------------------------------
                OcSectionHeader(stringResource(R.string.settings_appearance_header))
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Dynamic color needs Android 12+ (API 31); below that
                        // the switch is dead weight — hidden (UI audit C8).
                        if (Build.VERSION.SDK_INT >= 31) {
                            OcToggleableRow(
                                checked = state.dynamicColor,
                                onCheckedChange = { enabled ->
                                    // Confirmation haptic, same pattern as mode switch (E-010).
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.setDynamicColor(enabled)
                                },
                                role = Role.Switch,
                                modifier = Modifier.fillMaxWidth(),
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
                                    onCheckedChange = null,
                                )
                            }
                        }
                    }
                }

                // ---- Backup (functional audit F-013) -------------------------
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
                        OcInfoRow(stringResource(R.string.settings_about_version), BuildConfig.VERSION_NAME)
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
                AppUpdateCard()
                Spacer(Modifier.height(32.dp))
            }
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Every known provider as a selectable chip — no truncation
                    // (M3E audit E-007) — and the chips stay visible the whole
                    // time (UI audit B3): picking one used to swap the whole
                    // picker for a raw-ID field, which read like the choice was
                    // undone and forced the user through the custom path.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        known.forEach { id ->
                            FilterChip(
                                selected = !custom && providerId == id,
                                onClick = {
                                    providerId = id
                                    custom = false
                                },
                                label = { Text(id) },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.settings_providers_custom),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (custom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { custom = true }.padding(vertical = 10.dp),
                    )
                }
                if (custom) {
                    // The raw-ID field only appears when the user explicitly
                    // opts out of the known list (UI audit B3).
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
                enabled = providerId.isNotBlank() && apiKey.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
