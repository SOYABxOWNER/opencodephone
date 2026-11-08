package io.github.oustery.opencodephone

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.padding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as OpenCodePhoneApp
        setContent {
            OpenCodePhoneTheme {
                val startup by app.startup.collectAsStateWithLifecycle()
                when (val state = startup) {
                    is AppStartup.Ready -> AppRoot(graph = state.graph)
                    AppStartup.Loading -> androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(R.string.startup_loading),
                        modifier = androidx.compose.ui.Modifier.padding(32.dp),
                    )
                    AppStartup.Failed -> androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier.padding(32.dp),
                    ) {
                        androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.startup_failed))
                        androidx.compose.material3.Button(onClick = { app.retryStartup() }) {
                            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.action_retry))
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
