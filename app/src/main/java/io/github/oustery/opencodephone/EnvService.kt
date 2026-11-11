package io.github.oustery.opencodephone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.oustery.opencodephone.core.linuxenv.ServerState
import io.github.oustery.opencodephone.core.model.AgentActivity
import io.github.oustery.opencodephone.core.model.BootstrapPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the `opencode serve` child process alive while the user works outside
 * the app. Without it, backgrounding the app would let the OS reclaim the
 * Linux environment process.
 *
 * The notification doubles as the task board: the shared [AgentActivity]
 * state renders "running / done" so a prompt keeps its status visible after
 * the user leaves the chat, and a partial wake lock keeps the streaming run
 * (and the shell behind it) from being frozen while the screen is off.
 */
class EnvService : LifecycleService() {

    private lateinit var graph: AppGraph

    /** Latest environment/bootstrap line; shown when no task note wins. */
    private var baseText: String? = null

    /** Transient agent-task line ("running…", "done") that wins over base. */
    private var taskText: String? = null

    /** Mirrors [AgentActivity.RUNNING] for the indeterminate progress bar. */
    private var taskRunning = false

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.env_service_starting)))
        lifecycleScope.launch {
            val startup = (application as OpenCodePhoneApp).startup.first { it !is AppStartup.Loading }
            if (startup !is AppStartup.Ready) {
                stopSelf()
                return@launch
            }
            graph = startup.graph
            observeGraph()
        }
    }

    private fun observeGraph() {
        lifecycleScope.launch {
            graph.runtime.serverState.collectLatest { state ->
                baseText = when (state) {
                    is ServerState.Running -> getString(R.string.env_service_running, state.port)
                    is ServerState.Starting -> getString(R.string.env_service_starting)
                    is ServerState.Failed -> getString(R.string.env_service_failed, state.message.take(80))
                    ServerState.Stopped -> getString(R.string.env_service_stopped)
                }
                render()
            }
        }
        // During setup the service keeps the app at foreground importance so
        // Android is less likely to kill the bootstrap child processes.
        lifecycleScope.launch {
            graph.bootstrap.state.collectLatest { state ->
                baseText = when (state.phase) {
                    BootstrapPhase.READY ->
                        if (graph.runtime.serverState.value is ServerState.Stopped) {
                            getString(R.string.env_service_stopped)
                        } else baseText
                    BootstrapPhase.FAILED ->
                        getString(R.string.env_service_failed, state.error?.take(80).orEmpty())
                    else -> state.label.ifEmpty { getString(R.string.bootstrap_preparing) }
                }
                render()
            }
        }
        // Agent task board: while a prompt runs, hold a wake lock (bounded)
        // and show the running state; on completion flash "done" briefly and
        // fall back to the environment line.
        lifecycleScope.launch {
            graph.agentActivity.collectLatest { activity ->
                when (activity) {
                    AgentActivity.RUNNING -> {
                        acquireWakeLock()
                        taskRunning = true
                        taskText = getString(R.string.env_service_task_running)
                        render()
                    }
                    AgentActivity.IDLE -> {
                        releaseWakeLock()
                        taskRunning = false
                        if (taskText == null) return@collectLatest
                        taskText = getString(R.string.env_service_task_done)
                        render()
                        delay(DONE_FLASH_MS)
                        if (taskText == getString(R.string.env_service_task_done)) {
                            taskText = null
                            render()
                        }
                    }
                }
            }
        }
    }

    private fun render() {
        updateNotification(taskText ?: baseText ?: getString(R.string.env_service_starting))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .also { it.acquire(WAKELOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return if ((application as OpenCodePhoneApp).startup.value is AppStartup.Failed) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    // Child lifetime is owned by AppGraph/ManagedServer, not notification
    // teardown. A delayed stopService from setup can overlap the next
    // startForegroundService; onDestroy must not kill that new child.

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.env_service_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val running = taskRunning
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_prompt)
            .setContentTitle(getString(R.string.env_service_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, running)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "opencodephone_env"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "opencodephone:agentTask"

        /** A run must never hold the CPU forever if an event is missed. */
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val DONE_FLASH_MS = 6_000L

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
