package io.github.oustery.opencodephone

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.oustery.opencodephone.core.designsystem.OpenCodePhoneTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as OpenCodePhoneApp
        setContent {
            val startup by app.startup.collectAsStateWithLifecycle()
            // Dynamic color follows the persisted preference as soon as the
            // settings store is ready; the brief Loading window keeps the
            // branded teal palette (M3E audit E-016).
            val dynamicColor = if (startup is AppStartup.Ready) {
                val graph = (startup as AppStartup.Ready).graph
                val enabled by graph.dynamicColor.collectAsStateWithLifecycle()
                enabled
            } else {
                false
            }
            OpenCodePhoneTheme(dynamicColor = dynamicColor) {
                val resetConfirm = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                if (resetConfirm.value && startup == AppStartup.Failed) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { resetConfirm.value = false },
                        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.startup_reset)) },
                        text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.startup_reset_warning)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                resetConfirm.value = false
                                app.retryStartup(resetSecureSettings = true)
                            }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.startup_reset)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { resetConfirm.value = false }) {
                                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
                            }
                        },
                    )
                }
                when (val state = startup) {
                    is AppStartup.Ready -> AppRoot(graph = state.graph)
                    // Wavy loader instead of bare text — expressive loading state,
                    // consistent with BootstrapScreen (M3E audit E-002).
                    AppStartup.Loading -> Column(
                        modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularWavyProgressIndicator(modifier = androidx.compose.ui.Modifier.size(48.dp))
                        Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                        androidx.compose.material3.Text(
                            androidx.compose.ui.res.stringResource(R.string.startup_loading),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppStartup.Failed -> androidx.compose.foundation.layout.Column(
                        // Edge-to-edge is enabled, so the failure headline must
                        // respect the status-bar cut-out and carry explicit
                        // typography (UI audit A2); centered like Loading for
                        // one consistent startup-state voice.
                        modifier = androidx.compose.ui.Modifier.fillMaxSize()
                            .statusBarsPadding().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.Text(
                            androidx.compose.ui.res.stringResource(R.string.startup_failed),
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                        androidx.compose.material3.Button(onClick = { app.retryStartup() }) {
                            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.action_retry))
                        }
                        androidx.compose.material3.TextButton(onClick = { resetConfirm.value = true }) {
                            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.startup_reset))
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Keep the environment alive while backgrounded.
        val graph = (application as OpenCodePhoneApp).graphOrNull ?: return
        if (graph.runtime.serverState.value is io.github.oustery.opencodephone.core.linuxenv.ServerState.Running) {
            ContextCompat.startForegroundService(this, android.content.Intent(this, EnvService::class.java))
        }
    }
}
