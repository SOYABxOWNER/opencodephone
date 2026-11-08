package io.github.oustery.opencodephone

import android.app.Application
import io.github.oustery.opencodephone.core.linuxenv.BootstrapManager
import io.github.oustery.opencodephone.core.linuxenv.EnvLayout
import io.github.oustery.opencodephone.core.linuxenv.EnvironmentRuntime
import io.github.oustery.opencodephone.core.opencode.ConnectionConfig
import io.github.oustery.opencodephone.core.opencode.OpenCodeClient
import io.github.oustery.opencodephone.core.settings.ServerMode
import io.github.oustery.opencodephone.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph — the app is small enough that a DI framework
 * would add more surface than it removes (see ADR-002).
 */
class AppGraph(context: Application) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsStore = SettingsStore(context)

    val layout: EnvLayout = EnvLayout(context)

    val runtime: EnvironmentRuntime = EnvironmentRuntime(layout)

    val bootstrap: BootstrapManager = BootstrapManager(layout, runtime, appScope)

    @Volatile
    var client: OpenCodeClient = OpenCodeClient(config = buildClientConfig(settings))
        private set

    fun refreshClient() {
        client = OpenCodeClient(config = buildClientConfig(settings))
    }

    private fun buildClientConfig(settings: SettingsStore): ConnectionConfig {
        val cfg = settings.serverConfig()
        return if (cfg.mode == ServerMode.REMOTE) {
            ConnectionConfig(cfg.host, cfg.port, cfg.username, cfg.password)
        } else {
            ConnectionConfig("127.0.0.1", cfg.port, cfg.username, cfg.password)
        }
    }

    companion object {
        fun buildClientConfig(settings: SettingsStore) = with(settings.serverConfig()) {
            if (mode == ServerMode.REMOTE) {
                ConnectionConfig(host, port, username, password)
            } else {
                ConnectionConfig("127.0.0.1", port, username, password)
            }
        }
    }
}

class OpenCodePhoneApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Auto-run bootstrap when the environment is not installed yet;
        // the UI observes state and shows progress.
        graph.bootstrap.start()
    }
}
