package io.github.oustery.opencodephone

import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.oustery.opencodephone.core.designsystem.OcChipTone
import io.github.oustery.opencodephone.core.designsystem.OcStatusChip
import io.github.oustery.opencodephone.core.linuxenv.ServerState
import io.github.oustery.opencodephone.core.model.AgentActivity
import io.github.oustery.opencodephone.core.model.BootstrapPhase
import io.github.oustery.opencodephone.core.settings.SetupChoice
import io.github.oustery.opencodephone.ui.bootstrap.BootstrapScreen
import io.github.oustery.opencodephone.ui.chat.ChatScreen
import io.github.oustery.opencodephone.ui.files.FilesScreen
import io.github.oustery.opencodephone.ui.settings.SettingsScreen
import io.github.oustery.opencodephone.ui.terminal.TerminalScreen
import kotlinx.coroutines.launch

object Routes {
    const val BOOTSTRAP = "bootstrap"
    const val CHAT = "chat"
    const val FILES = "files"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"
}

/**
 * Drawer handle published to every [ScreenHeader]: screens render the menu
 * button when it is non-null (phones) and skip it on wide layouts where the
 * navigation rail is permanently visible.
 */
val LocalMainDrawer = staticCompositionLocalOf<androidx.compose.material3.DrawerState?> { null }

@Composable
fun AppRoot(graph: AppGraph) {
    val navController = rememberNavController()
    val bootstrapState by graph.bootstrap.state.collectAsStateWithLifecycle()
    val serverState by graph.runtime.serverState.collectAsStateWithLifecycle()
    val serverConfiguration by graph.serverConfiguration.collectAsStateWithLifecycle()
    val setupChoice by graph.setupChoice.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Route between the bootstrap wizard and the main experience. The
    // remote-first path (F-022) reaches the main experience without a
    // local environment until the user asks for one in Settings.
    LaunchedEffect(bootstrapState.phase, setupChoice) {
        val toMain = bootstrapState.phase == BootstrapPhase.READY ||
            setupChoice == SetupChoice.REMOTE_ONLY
        val current = navController.currentBackStackEntry?.destination?.route
        when {
            toMain -> {
                if (current != Routes.CHAT) {
                    navController.navigate(Routes.CHAT) { popUpTo(0) }
                }
            }
            else -> {
                if (current != Routes.BOOTSTRAP) {
                    navController.navigate(Routes.BOOTSTRAP) { popUpTo(0) }
                }
            }
        }
    }

    // Autostart is owned by AppGraph (independent of navigation). Keep
    // setup and server startup foregrounded, and never stop the service in
    // the READY -> Starting transition. A running agent task keeps the
    // service up in every mode (also remote-only), so backgrounding the app
    // cannot freeze the work in progress.
    val agentActivity by graph.agentActivity.collectAsStateWithLifecycle()
    LaunchedEffect(bootstrapState.phase, serverState, serverConfiguration.mode, setupChoice, agentActivity) {
        when {
            agentActivity == AgentActivity.RUNNING -> EnvService.start(context)
            bootstrapState.phase == BootstrapPhase.READY &&
                serverConfiguration.mode == io.github.oustery.opencodephone.core.settings.ServerMode.REMOTE -> EnvService.stop(context)
            serverState is ServerState.Starting || serverState is ServerState.Running -> EnvService.start(context)
            bootstrapState.phase == BootstrapPhase.FAILED || serverState is ServerState.Failed -> EnvService.stop(context)
            // Remote-first (F-022): keep the foreground service quiet while
            // the user works against an external server without a local env.
            bootstrapState.phase != BootstrapPhase.READY && setupChoice != SetupChoice.REMOTE_ONLY -> EnvService.start(context)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
        val isWide = maxWidth >= 600.dp
        if (isWide) {
            // Wide layouts keep a permanent sidebar: the rail is always there,
            // so the header menu button is not needed.
            Row(modifier = Modifier.fillMaxSize()) {
                AppNavRail(navController)
                Surface(modifier = Modifier.weight(1f)) {
                    AppNavHost(navController, graph)
                }
            }
        } else {
            // Phones: sidebar navigation. The drawer hosts the destinations,
            // the live server status and the agent task board; the bottom bar
            // is gone in favour of one consistent nav surface.
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val drawerScope = rememberCoroutineScope()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            val drawerAvailable = currentRoute != Routes.BOOTSTRAP
            androidx.compose.runtime.CompositionLocalProvider(
                LocalMainDrawer provides drawerState.takeIf { drawerAvailable },
            ) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = drawerAvailable,
                    drawerContent = {
                        if (drawerAvailable) {
                            AppDrawerContent(
                                navController = navController,
                                currentRoute = currentRoute,
                                serverState = serverState,
                                agentActivity = agentActivity,
                                closeDrawer = { drawerScope.launch { drawerState.close() } },
                            )
                        }
                    },
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppNavHost(navController, graph)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(navController: androidx.navigation.NavHostController, graph: AppGraph) {
    NavHost(
        navController = navController,
        startDestination = Routes.BOOTSTRAP,
        // Subtle spatial transition instead of an instant swap (M3E audit
        // E-013): fade keeps tab switches calm, the slight scale-in gives
        // forward/backward moves a direction.
        enterTransition = {
            fadeIn(animationSpec = tween(durationMillis = 220)) + scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(durationMillis = 220),
            )
        },
        exitTransition = { fadeOut(animationSpec = tween(durationMillis = 150)) },
        popEnterTransition = {
            fadeIn(animationSpec = tween(durationMillis = 220)) + scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(durationMillis = 220),
            )
        },
        popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 150)) },
    ) {
        composable(Routes.BOOTSTRAP) { BootstrapScreen(graph) }
        composable(Routes.CHAT) { ChatScreen(graph, onOpenSettings = { navigateTo(navController, Routes.SETTINGS) }) }
        composable(Routes.FILES) { FilesScreen(graph) }
        composable(Routes.TERMINAL) { TerminalScreen(graph) }
        composable(Routes.SETTINGS) { SettingsScreen(graph) }
    }
}

private data class Destination(
    val route: String,
    val labelRes: Int,
    val iconRes: Int,
)

private val destinations = listOf(
    Destination(Routes.CHAT, R.string.tab_chat, R.drawable.ic_tab_chat),
    Destination(Routes.FILES, R.string.tab_files, R.drawable.ic_tab_files),
    Destination(Routes.TERMINAL, R.string.tab_terminal, R.drawable.ic_tab_terminal),
    Destination(Routes.SETTINGS, R.string.tab_settings, R.drawable.ic_tab_settings),
)

@Composable
private fun AppDrawerContent(
    navController: NavHostController,
    currentRoute: String?,
    serverState: ServerState,
    agentActivity: AgentActivity,
    closeDrawer: () -> Unit,
) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            // Brand block with the live state of the machine: server line and
            // agent task board, so the drawer doubles as a status glance.
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                OcStatusChip(
                    text = when (serverState) {
                        is ServerState.Running -> stringResource(R.string.env_service_running, serverState.port)
                        is ServerState.Starting -> stringResource(R.string.env_service_starting)
                        is ServerState.Failed -> stringResource(R.string.env_service_failed, serverState.message.take(60))
                        ServerState.Stopped -> stringResource(R.string.env_service_stopped)
                    },
                    tone = when (serverState) {
                        is ServerState.Running -> OcChipTone.PRIMARY
                        is ServerState.Failed -> OcChipTone.ERROR
                        else -> OcChipTone.NEUTRAL
                    },
                )
                OcStatusChip(
                    text = if (agentActivity == AgentActivity.RUNNING) {
                        stringResource(R.string.env_service_task_running)
                    } else {
                        stringResource(R.string.agent_status_idle)
                    },
                    tone = if (agentActivity == AgentActivity.RUNNING) OcChipTone.TERTIARY else OcChipTone.NEUTRAL,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            destinations.forEach { dest ->
                NavigationDrawerItem(
                    label = { Text(stringResource(dest.labelRes)) },
                    icon = { Icon(painterResource(dest.iconRes), contentDescription = null) },
                    selected = currentRoute == dest.route,
                    onClick = {
                        closeDrawer()
                        navigateTo(navController, dest.route)
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            // Quiet version footer: the drawer doubles as an about glance.
            Text(
                text = stringResource(R.string.nav_app_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun AppNavRail(navController: androidx.navigation.NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    NavigationRail {
        Spacer(Modifier.height(48.dp))
        destinations.forEach { dest ->
            NavigationRailItem(
                selected = currentRoute == dest.route,
                onClick = { navigateTo(navController, dest.route) },
                icon = { Icon(painterResource(dest.iconRes), contentDescription = null) },
                label = { Text(stringResource(dest.labelRes)) },
            )
        }
    }
}

private fun navigateTo(navController: androidx.navigation.NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Screen header used by all main screens (single focal title, M3E type). */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // Leading menu button whenever a modal drawer is available (phones).
        val menu: @Composable RowScope.() -> Unit = { NavMenuButton() }
        if (maxWidth < 480.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    menu()
                    Text(title, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                menu()
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
                Row(modifier = Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End, content = actions)
            }
        }
    }
}

/**
 * The hamburger that opens the main drawer. Renders nothing on wide layouts
 * (the rail is permanently visible) and during bootstrap (no destinations).
 */
@Composable
fun NavMenuButton() {
    val drawer = LocalMainDrawer.current
    val scope = rememberCoroutineScope()
    if (drawer != null) {
        IconButton(onClick = { scope.launch { drawer.open() } }) {
            Icon(
                painterResource(R.drawable.ic_menu),
                contentDescription = stringResource(R.string.nav_open_menu),
            )
        }
    }
}

/** Content container that avoids the bottom navigation bar overlap. */
@Composable
fun ScreenBody(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
        content = content,
    )
}
