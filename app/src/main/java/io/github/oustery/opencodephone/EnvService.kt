package io.github.oustery.opencodephone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.oustery.opencodephone.core.linuxenv.ServerState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the `opencode serve` child process alive while the user works outside
 * the app. Without it, backgrounding the app would let the OS reclaim the
 * Linux environment process.
 */
class EnvService : LifecycleService() {

    private lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        graph = (application as OpenCodePhoneApp).graph
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.env_service_starting)))
        lifecycleScope.launch {
            graph.runtime.serverState.collectLatest { state ->
                val text = when (state) {
                    is ServerState.Running -> getString(R.string.env_service_running, state.port)
                    is ServerState.Starting -> getString(R.string.env_service_starting)
                    is ServerState.Failed -> getString(R.string.env_service_failed, state.message.take(80))
                    ServerState.Stopped -> getString(R.string.env_service_stopped)
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        // The service being torn down means the user no longer needs the env;
        // stop the child server so no orphan processes linger.
        if (!graph.runtime.isServerAlive()) {
            graph.runtime.stopServer()
        }
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.env_service_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_prompt)
            .setContentTitle(getString(R.string.env_service_title))
            .setContentText(text)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "opencodephone_env"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, EnvService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EnvService::class.java))
        }
    }
}
