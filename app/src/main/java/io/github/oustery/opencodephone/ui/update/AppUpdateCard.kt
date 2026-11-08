package io.github.oustery.opencodephone.ui.update

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oustery.opencodephone.BuildConfig
import io.github.oustery.opencodephone.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppUpdateCard() {
    val vm: AppUpdateViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME))
            Text(stringResource(when (state.phase) {
                UpdatePhase.IDLE -> R.string.update_note
                UpdatePhase.CHECKING -> R.string.update_checking
                UpdatePhase.CURRENT -> R.string.update_current
                UpdatePhase.AVAILABLE -> R.string.update_available
                UpdatePhase.DOWNLOADING -> R.string.update_downloading
                UpdatePhase.READY -> R.string.update_ready
                UpdatePhase.ERROR -> R.string.update_error
            }, state.version, state.progress))
            when (state.phase) {
                UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING -> {
                    if (state.phase == UpdatePhase.DOWNLOADING) LinearWavyProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = vm::cancel) { Text(stringResource(R.string.action_cancel)) }
                }
                UpdatePhase.AVAILABLE -> Button(onClick = vm::download) { Text(stringResource(R.string.update_download)) }
                UpdatePhase.READY -> Button(onClick = { vm.install(context) }) { Text(stringResource(R.string.update_install)) }
                else -> Button(onClick = vm::check) { Text(stringResource(R.string.update_check)) }
            }
        }
    }
}
