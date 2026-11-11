package io.github.oustery.opencodephone

import android.app.Application
import android.content.Context
import io.github.oustery.opencodephone.core.linuxenv.BootstrapManager
import io.github.oustery.opencodephone.core.linuxenv.EnvLayout
import io.github.oustery.opencodephone.core.linuxenv.EnvironmentRuntime
import io.github.oustery.opencodephone.core.model.AgentActivity
import io.github.oustery.opencodephone.core.opencode.ConnectionConfig
import io.github.oustery.opencodephone.core.opencode.OpenCodeClient
import io.github.oustery.opencodephone.core.opencode.OpenCodeConsoleClient
import io.github.oustery.opencodephone.core.settings.ServerMode
import io.github.oustery.opencodephone.core.settings.SetupChoice
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

    /** OpenCode console (opencode.ai) client for the account device flow. */
    val consoleClient = OpenCodeConsoleClient()

    val layout: EnvLayout = EnvLayout(context)

    val runtime: EnvironmentRuntime = EnvironmentRuntime(layout)

    val bootstrap: BootstrapManager = BootstrapManager(layout, runtime, appScope, settings.opencodeVersionPin())

    private val _serverConfiguration = MutableStateFlow(settings.effectiveServerConfig())
    val serverConfiguration: StateFlow<ServerConfig> = _serverConfiguration

    private val _localServerEnabled = MutableStateFlow(true)
    val localServerEnabled: StateFlow<Boolean> = _localServerEnabled

    fun stopLocalServer() { _localServerEnabled.value = false }
    fun startLocalServer() { _localServerEnabled.value = true }

    // Appearance preference read once the graph is ready; the theme observes
    // this flow so the Settings switch applies immediately (M3E audit E-016).
    private val _dynamicColor = MutableStateFlow(settings.dynamicColor())
    val dynamicColor: StateFlow<Boolean> = _dynamicColor

    fun setDynamicColor(enabled: Boolean) {
        settings.setDynamicColor(enabled)
        _dynamicColor.value = enabled
    }

    // Remote-first onboarding choice (functional audit F-022). UNSET keeps
    // the legacy auto-bootstrap; REMOTE_ONLY defers the Debian download.
    private val _setupChoice = MutableStateFlow(settings.setupChoice())
    val setupChoice: StateFlow<SetupChoice> = _setupChoice

    fun chooseSetup(choice: SetupChoice) {
        settings.setSetupChoice(choice)
        _setupChoice.value = choice
        if (choice == SetupChoice.LOCAL) bootstrap.start()
    }

    // Minimal shared project context (functional audit F-001): the repository
    // picked in Files is the working context for chat session titles and the
    // terminal working directory, persisted across restarts.
    private val _selectedProject = MutableStateFlow(settings.selectedProject())
    val selectedProject: StateFlow<String?> = _selectedProject

    fun selectProject(relativePath: String?) {
        if (_selectedProject.value == relativePath) return
        settings.setSelectedProject(relativePath)
        _selectedProject.value = relativePath
    }

    // Shared agent-task status for the persistent notification and the
    // sidebar footer: "the task is running" survives leaving the chat screen,
    // so the user sees completion outside the app as well.
    private val _agentActivity = MutableStateFlow(AgentActivity.IDLE)
    val agentActivity: StateFlow<AgentActivity> = _agentActivity

    fun setAgentActivity(activity: AgentActivity) {
        if (_agentActivity.value != activity) _agentActivity.value = activity
    }

    init {
        appScope.launch {
            observeServerLifecycle(
                enabled = localServerEnabled,
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
        if (cfg.mode != ServerMode.LOCAL || !localServerEnabled.value) return
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
        if (!localServerEnabled.value) startLocalServer()
        else appScope.launch { ensureLocalServer(restart = true) }
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
            // The console access token doubles as the `opencode` provider key;
            // refresh it before the push when it is close to expiry.
            refreshOpencodeAccountToken()
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

    /**
     * The console access token expires; refresh it when it is within five
     * minutes of expiry so the pushed `opencode` provider key stays valid.
     * A failed refresh keeps the stored token (the sync still pushes it and
     * the account screen surfaces the error on the next attempt).
     */
    private suspend fun refreshOpencodeAccountToken() {
        val account = settings.opencodeAccount() ?: return
        if (account.expiresAtMs > System.currentTimeMillis() + 5 * 60_000L) return
        val refreshed = try {
            consoleClient.refresh(account.refreshToken)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        val updated = account.copy(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken,
            expiresAtMs = System.currentTimeMillis() + refreshed.expiresInMs,
        )
        settings.setOpencodeAccount(updated)
        settings.setProviderKey(OPENCODE_ACCOUNT_PROVIDER_ID, updated.accessToken)
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
        /**
         * Provider id the signed-in console token is pushed under; upstream
         * reads it from `auth.json["opencode"]` to unlock the paid Zen models.
         */
        const val OPENCODE_ACCOUNT_PROVIDER_ID = "opencode"

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
                // Remote-first onboarding (F-022): a user who chose the
                // server path must not be forced into the Debian download.
                // UNSET keeps the legacy auto-bootstrap so existing installs
                // (environment already present) are unaffected.
                if (graph.setupChoice.value != SetupChoice.REMOTE_ONLY) graph.bootstrap.start()
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
