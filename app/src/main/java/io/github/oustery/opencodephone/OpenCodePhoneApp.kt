package io.github.oustery.opencodephone

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.oustery.opencodephone.core.linuxenv.ServerLaunchConfig
import io.github.oustery.opencodephone.core.linuxenv.observeServerLifecycle
import io.github.oustery.opencodephone.core.model.BootstrapPhase
import io.github.oustery.opencodephone.core.settings.ServerConfig

/**
 * Manual dependency graph — the app is small enough that a DI framework
 * would add more surface than it removes (see ADR-002).
 */
class AppGraph(context: Application) {

    val appContext: Context = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsStore = SettingsStore(context)

    val layout: EnvLayout = EnvLayout(context)

    val runtime: EnvironmentRuntime = EnvironmentRuntime(layout)

    val bootstrap: BootstrapManager = BootstrapManager(layout, runtime, appScope, settings.opencodeVersionPin())

    private val _serverConfiguration = MutableStateFlow(settings.effectiveServerConfig())
    val serverConfiguration: StateFlow<ServerConfig> = _serverConfiguration

    init {
        appScope.launch {
            observeServerLifecycle(
                ready = bootstrap.state.map { it.phase == BootstrapPhase.READY }.distinctUntilChanged(),
                configuration = serverConfiguration.map { cfg ->
                    if (cfg.mode == ServerMode.LOCAL) ServerLaunchConfig(cfg.port, cfg.username, cfg.password) else null
                }.distinctUntilChanged(),
                start = { ensureLocalServer() },
                stop = {
                    withContext(Dispatchers.IO) { runtime.stopServer() }
                    if (bootstrap.state.value.phase == BootstrapPhase.READY) EnvService.stop(appContext)
                },
            )
        }
    }

    suspend fun ensureLocalServer(restart: Boolean = false) {
        val cfg = settings.effectiveServerConfig()
        if (cfg.mode != ServerMode.LOCAL) return
        // Establish foreground importance before launching the child, not only
        // after health becomes green (which used to leave startup unprotected).
        try {
            check(bootstrap.state.value.phase == BootstrapPhase.READY) { "Finish environment setup before starting the server." }
            EnvService.start(appContext)
            runtime.startServer(cfg.port, cfg.password, cfg.username, restart = restart)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runtime.reportServerFailure("Cannot start local server: ${e.message}")
        }
    }

    fun restartLocalServer() {
        appScope.launch { ensureLocalServer(restart = true) }
    }

    @Volatile
    var client: OpenCodeClient = OpenCodeClient(config = buildClientConfig(settings))
        private set

    private val credentialsLock = kotlinx.coroutines.sync.Mutex()
    val credentialsRevision = MutableStateFlow(0L)
    fun credentialsChanged() { credentialsRevision.value += 1 }

    suspend fun syncProviderCredentials(target: OpenCodeClient) = withContext(Dispatchers.IO) {
        credentialsLock.lock()
        try {
            val cfg = serverConfiguration.value
            val expected = ConnectionConfig(if (cfg.mode == ServerMode.LOCAL) "127.0.0.1" else cfg.host,
                cfg.port, cfg.username, cfg.password)
            if (target.config != expected || !settings.providerSyncAllowed(cfg)) return@withContext
            val keys = settings.providerKeys()
            var managed = settings.managedProviders(cfg)
            for (id in managed - keys.map { it.providerId }.toSet()) {
                target.removeProviderAuth(id)
                managed = managed - id
                settings.recordManagedProviders(cfg, managed)
            }
            for (key in keys) {
                settings.recordManagedProviders(cfg, managed + key.providerId)
                managed = managed + key.providerId
                target.setProviderAuth(key.providerId, target.apiCredentials(key.providerId, key.apiKey))
            }
        } finally { credentialsLock.unlock() }
    }

    fun refreshClient() {
        client = OpenCodeClient(config = buildClientConfig(settings))
        _serverConfiguration.value = settings.effectiveServerConfig()
    }

    private fun buildClientConfig(settings: SettingsStore): ConnectionConfig {
        val cfg = settings.effectiveServerConfig()
        return if (cfg.mode == ServerMode.REMOTE) {
            ConnectionConfig(cfg.host, cfg.port, cfg.username, cfg.password)
        } else {
            ConnectionConfig("127.0.0.1", cfg.port, cfg.username, cfg.password)
        }
    }

    companion object {
        fun buildClientConfig(settings: SettingsStore) = with(settings.effectiveServerConfig()) {
            if (mode == ServerMode.REMOTE) {
                ConnectionConfig(host, port, username, password)
            } else {
                ConnectionConfig("127.0.0.1", port, username, password)
            }
        }
    }
}

sealed interface AppStartup {
    data object Loading : AppStartup
    data object Failed : AppStartup
    data class Ready(val graph: AppGraph) : AppStartup
}

class OpenCodePhoneApp : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _startup = MutableStateFlow<AppStartup>(AppStartup.Loading)
    val startup: StateFlow<AppStartup> = _startup
    val graphOrNull: AppGraph? get() = (_startup.value as? AppStartup.Ready)?.graph
    private var startupJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        retryStartup()
    }

    @Synchronized
    fun retryStartup(resetSecureSettings: Boolean = false) {
        if (resetSecureSettings && _startup.value != AppStartup.Failed) return
        if (startupJob?.isActive == true || graphOrNull != null) return
        _startup.value = AppStartup.Loading
        startupJob = startupScope.launch {
            try {
                if (resetSecureSettings) SettingsStore.resetEncryptedSettings(this@OpenCodePhoneApp)
                val graph = AppGraph(this@OpenCodePhoneApp)
                _startup.value = AppStartup.Ready(graph)
                graph.bootstrap.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Do not delete/recreate encrypted prefs or log credential-bearing data.
                android.util.Log.e("OpenCodePhone", "Startup failed (${e.javaClass.simpleName})")
                _startup.value = AppStartup.Failed
            }
        }
    }
}
