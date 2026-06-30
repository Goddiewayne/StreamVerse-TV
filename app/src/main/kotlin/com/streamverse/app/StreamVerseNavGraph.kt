package com.streamverse.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streamverse.app.ui.category.ChannelListScreen
import com.streamverse.app.ui.components.LocalLiveChannels
import com.streamverse.app.ui.guide.TvGuideScreen
import com.streamverse.app.ui.favorites.FavoritesScreen
import com.streamverse.app.ui.home.HomeScreen
import com.streamverse.app.ui.player.LocalMiniPlayerInset
import com.streamverse.app.ui.player.MiniPlayerGap
import com.streamverse.app.ui.player.MiniPlayerHeight
import com.streamverse.app.ui.player.PlayerHost
import com.streamverse.app.ui.player.PlayerViewModel
import com.streamverse.app.ui.schedule.ScheduleScreen
import com.streamverse.app.ui.search.SearchScreen
import com.streamverse.app.ui.settings.SettingsScreen
import com.streamverse.app.ui.source.SourceManagementScreen
import com.streamverse.app.ui.theme.CyberCyan
import com.streamverse.app.ui.theme.ElectricViolet
import com.streamverse.app.ui.theme.NavyCard
import com.streamverse.app.ui.theme.SpaceNavy

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    data object Home     : Screen("home",      "Home",      Icons.Outlined.Home,          Icons.Filled.Home)
    data object Search   : Screen("search",    "Search",    Icons.Outlined.Search,        Icons.Filled.Search)
    data object Favorites: Screen("favorites", "Favourites",Icons.Outlined.FavoriteBorder,Icons.Filled.Favorite)
    data object Settings : Screen("settings",  "Settings",  Icons.Outlined.Settings,      Icons.Filled.Settings)
}

object PlayerScreenRoute {
    // Param-less: the channel being played lives in the shared (activity-scoped) PlayerViewModel, not
    // in the route. The route only signals "show the full player page"; backing out of it leaves the
    // channel playing in the minimized bar.
    const val route = "player"
}

object ScheduleScreenRoute {
    const val route = "schedule"
}

object TvGuideRoute {
    const val route = "tvguide"
}

object ChannelListRoute {
    const val route = "channels/{type}/{value}"
    fun createRoute(type: String, value: String) =
        "channels/${type}/${value.replace("/", "%2F")}"
}

object SourceManagementRoute {
    const val route = "channel_sources"
}

val bottomNavItems = listOf(Screen.Home, Screen.Search, Screen.Favorites, Screen.Settings)

private val TAB_ENTER  = fadeIn(tween(220))
private val TAB_EXIT   = fadeOut(tween(180))
private val PUSH_ENTER = slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(280))
private val PUSH_EXIT  = fadeOut(tween(200))
private val POP_ENTER  = fadeIn(tween(220))
private val POP_EXIT   = slideOutVertically(tween(280)) { it / 2 } + fadeOut(tween(200))

@UnstableApi
@Composable
fun StreamVerseNavGraph(initialChannelId: String? = null, onNavigated: () -> Unit = {}) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    // Shared, Activity-scoped player so playback survives navigation: backing out of the player page
    // keeps the channel alive in the minimized bar, and opening another channel seamlessly swaps it.
    val activity = LocalContext.current as? ComponentActivity
    val playerViewModel: PlayerViewModel =
        if (activity != null) hiltViewModel(activity) else hiltViewModel()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()

    // App-wide LIVE Availability Index → provided to every ChannelCard via LocalLiveChannels.
    val liveIndexViewModel: LiveIndexViewModel = hiltViewModel()
    val liveChannelIds by liveIndexViewModel.liveChannelIds.collectAsStateWithLifecycle()

    val openChannel: (String) -> Unit = { id ->
        playerViewModel.open(id)
        navController.navigate(PlayerScreenRoute.route) { launchSingleTop = true }
    }

    LaunchedEffect(initialChannelId) {
        android.util.Log.d("NavGraph", "LaunchedEffect: initialChannelId=$initialChannelId")
        if (initialChannelId != null) {
            android.util.Log.d("NavGraph", "Calling openChannel($initialChannelId)")
            openChannel(initialChannelId)
            onNavigated()
            android.util.Log.d("NavGraph", "openChannel completed")
        }
    }
    val currentDestination = navBackStackEntry?.destination
    val onPlayerRoute = currentDestination?.route == PlayerScreenRoute.route

    val showBottomBar = bottomNavItems.any { screen ->
        currentDestination?.hierarchy?.any { it.route == screen.route } == true
    }

    Scaffold(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = SpaceNavy,
                    tonalElevation = 0.dp,
                ) {
                    bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon else screen.icon,
                                    contentDescription = screen.label,
                                )
                            },
                            label = {
                                Text(
                                    text = screen.label,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CyberCyan,
                                selectedTextColor = CyberCyan,
                                unselectedIconColor = Color(0xFF475569),
                                unselectedTextColor = Color(0xFF475569),
                                indicatorColor = CyberCyan.copy(alpha = 0.18f),
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // When the minimized bar is showing (player active but not expanded), tell scrolling screens
        // to reserve room for it so their last row clears the floating bar.
        val miniInset = if (playerState.active && !onPlayerRoute) MiniPlayerHeight + MiniPlayerGap else 0.dp
        Box(modifier = Modifier.fillMaxSize()) {
          CompositionLocalProvider(
              LocalMiniPlayerInset provides miniInset,
              LocalLiveChannels provides liveChannelIds,
          ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background),
                enterTransition = { TAB_ENTER },
                exitTransition = { TAB_EXIT },
                popEnterTransition = { POP_ENTER },
                popExitTransition = { POP_EXIT },
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        onChannelClick = openChannel,
                        onGuideClick = { navController.navigate(TvGuideRoute.route) },
                        onSeeAllClick = { type, value -> navController.navigate(ChannelListRoute.createRoute(type, value)) },
                        onSearchClick = {
                            navController.navigate(Screen.Search.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(TvGuideRoute.route) {
                    TvGuideScreen(
                        onChannelClick = openChannel,
                        onBackClick = { navController.popBackStack() },
                    )
                }
                composable(Screen.Search.route) {
                    SearchScreen(
                        onChannelClick = openChannel,
                    )
                }
                composable(Screen.Favorites.route) {
                    FavoritesScreen(
                        onChannelClick = openChannel,
                    )
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onManageSources = { navController.navigate(SourceManagementRoute.route) },
                    )
                }
                composable(SourceManagementRoute.route) {
                    SourceManagementScreen(
                        onBackClick = { navController.popBackStack() },
                    )
                }
                // The player page is drawn by the persistent overlay below; this destination just
                // owns the back stack entry (so Back collapses the player to the mini bar) and hides
                // the bottom navigation while the full page is up.
                composable(
                    route = PlayerScreenRoute.route,
                    enterTransition = { PUSH_ENTER },
                    exitTransition = { PUSH_EXIT },
                    popEnterTransition = { POP_ENTER },
                    popExitTransition = { POP_EXIT },
                ) {
                    // Transparent: the full player UI is drawn by the persistent overlay (which is
                    // opaque when expanded). Keeping this empty avoids a black flash during the
                    // collapse-to-mini pop transition.
                    Box(modifier = Modifier.fillMaxSize())
                }
                composable(
                    route = ScheduleScreenRoute.route,
                    enterTransition = { PUSH_ENTER },
                    exitTransition = { PUSH_EXIT },
                    popEnterTransition = { POP_ENTER },
                    popExitTransition = { POP_EXIT },
                ) {
                    ScheduleScreen(
                        onChannelClick = openChannel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = ChannelListRoute.route,
                    arguments = listOf(
                        navArgument("type") { type = NavType.StringType },
                        navArgument("value") { type = NavType.StringType },
                    ),
                    enterTransition = { PUSH_ENTER },
                    exitTransition = { PUSH_EXIT },
                    popEnterTransition = { POP_ENTER },
                    popExitTransition = { POP_EXIT },
                ) {
                    ChannelListScreen(
                        onChannelClick = openChannel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
          }

            // Persistent player surface — full page when on the player route, otherwise the minimized
            // "now playing" bar. Kept mounted across navigation so playback never stops and the video
            // is never torn down (no re-tuning static when minimizing / going fullscreen).
            if (playerState.active) {
                PlayerHost(
                    viewModel = playerViewModel,
                    expanded = onPlayerRoute,
                    // Sit above the bottom nav (tabs) or the system nav bar (pushed screens).
                    bottomInset = innerPadding.calculateBottomPadding(),
                    onExpand = {
                        navController.navigate(PlayerScreenRoute.route) { launchSingleTop = true }
                    },
                    onCollapse = { navController.popBackStack() },
                    onClose = {
                        if (onPlayerRoute) navController.popBackStack()
                        playerViewModel.close()
                    },
                )
            }
        }
    }
}
