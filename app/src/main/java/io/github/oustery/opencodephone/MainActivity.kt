package io.github.oustery.opencodephone

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
        val graph = (application as OpenCodePhoneApp).graph
        setContent {
            OpenCodePhoneTheme {
                AppRoot(graph = graph)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Keep the environment alive while backgrounded.
        val graph = (application as OpenCodePhoneApp).graph
        if (graph.runtime.serverState.value is io.github.oustery.opencodephone.core.linuxenv.ServerState.Running) {
            ContextCompat.startForegroundService(this, android.content.Intent(this, EnvService::class.java))
        }
    }
}
