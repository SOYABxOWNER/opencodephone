package io.github.oustery.opencodephone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.oustery.opencodephone.core.linuxenv.ServerState
import io.github.oustery.opencodephone.core.model.BootstrapPhase
import io.github.oustery.opencodephone.ui.bootstrap.BootstrapScreen
import io.github.oustery.opencodephone.ui.chat.ChatScreen
import io.github.oustery.opencodephone.ui.files.FilesScreen
import io.github.oustery.opencodephone.ui.settings.SettingsScreen
import io.github.oustery.opencodephone.ui.terminal.TerminalScreen

object Routes {
    const val BOOTSTRAP = "bootstrap"
    const val CHAT = "chat"
    const val FILES = "files"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"
}

@Composable
fun AppRoot(graph: AppGraph) {
    val navController = rememberNavController()
    val bootstrapState by graph.bootstrap.state.collectAsStateWithLifecycle()
    val serverState by graph.runtime.serverState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Route between the bootstrap wizard and the main experience.
    LaunchedEffect(bootstrapState.phase) {
        val current = navController.currentBackStackEntry?.destination?.route
        when (bootstrapState.phase) {
            BootstrapPhase.READY -> {
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

    // Keep the environment alive in background through the foreground service.
    LaunchedEffect(serverState) {
        if (serverState is ServerState.Running) {
            EnvService.start(context)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp
        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                AppNavRail(navController)
                Surface(modifier = Modifier.weight(1f)) {
                    AppNavHost(navController, graph)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(modifier = Modifier.weight(1f)) {
                    AppNavHost(navController, graph)
                }
                AppNavBar(navController)
            }
        }
    }
}

@Composable
private fun AppNavHost(navController: androidx.navigation.NavHostController, graph: AppGraph) {
    NavHost(navController = navController, startDestination = Routes.BOOTSTRAP) {
        composable(Routes.BOOTSTRAP) { BootstrapScreen(graph) }
        composable(Routes.CHAT) { ChatScreen(graph) }
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
private fun AppNavBar(navController: androidx.navigation.NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    NavigationBar {
        destinations.forEach { dest ->
            NavigationBarItem(
                selected = currentRoute == dest.route,
                onClick = { navigateTo(navController, dest.route) },
                icon = { Icon(painterResource(dest.iconRes), contentDescription = null) },
                label = { Text(stringResource(dest.labelRes)) },
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
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
