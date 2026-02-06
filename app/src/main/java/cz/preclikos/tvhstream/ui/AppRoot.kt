package cz.preclikos.tvhstream.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cz.preclikos.tvhstream.settings.SecurePasswordStore
import cz.preclikos.tvhstream.settings.SettingsStore
import cz.preclikos.tvhstream.ui.player.VideoPlayerScreen
import cz.preclikos.tvhstream.viewmodels.AppConnectionViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.system.exitProcess

object Routes {
    const val CHANNELS = "channels"
    const val EPG = "epg"
    const val SETTINGS = "settings"
    const val PLAYER = "player"
    fun player(channelId: Int, serviceId: Int, channelName: String) =
        "player/$channelId/$serviceId/${android.net.Uri.encode(channelName)}"
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val activity = context as? Activity

    val appVm: AppConnectionViewModel = koinViewModel()
    val status by appVm.status.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun closeDrawer() = scope.launch { drawerState.close() }
    fun openDrawer() = scope.launch { drawerState.open() }

    // Helper: navigate to top-level without stacking duplicates
    fun navigateTop(route: String) {
        nav.navigate(route) {
            popUpTo(Routes.CHANNELS) { inclusive = false }
            launchSingleTop = true
            restoreState = true
        }
        closeDrawer()
    }

    BackHandler {
        val current = nav.currentBackStackEntry?.destination?.route
        when (current) {
            Routes.CHANNELS -> {
                activity?.finishAffinity()
                exitProcess(0)
            }

            Routes.EPG, Routes.SETTINGS -> {
                // back z top-level obrazovek vrati do Channels
                nav.navigate(Routes.CHANNELS) {
                    popUpTo(Routes.CHANNELS) { inclusive = false }
                    launchSingleTop = true
                }
            }

            else -> nav.popBackStack()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "TVHStream",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Channels") },
                    selected = nav.currentBackStackEntry?.destination?.route == Routes.CHANNELS,
                    onClick = { navigateTop(Routes.CHANNELS) },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(
                    label = { Text("EPG") },
                    selected = nav.currentBackStackEntry?.destination?.route == Routes.EPG,
                    onClick = { navigateTop(Routes.EPG) },
                    icon = { Icon(Icons.Default.Event, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Spacer(Modifier.weight(1f))

                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = nav.currentBackStackEntry?.destination?.route == Routes.SETTINGS,
                    onClick = { navigateTop(Routes.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = Routes.CHANNELS
            ) {
                composable(Routes.CHANNELS) {
                    ChannelsScreen(
                        onPlay = { channelId, serviceId, name ->
                            nav.navigate(Routes.player(channelId, serviceId, name))
                        },
                        onOpenSettings = { navigateTop(Routes.SETTINGS) },
                        onOpenDrawer = { openDrawer() } // <- přidej do ChannelsScreen
                    )
                }

                composable(Routes.EPG) {
                    EpgScreen(
                        onPlay = { channelId, serviceId, name ->
                            nav.navigate(Routes.player(channelId, serviceId, name))
                        },
                        onOpenDrawer = { openDrawer() }
                    )
                }

                composable(Routes.SETTINGS) {
                    val settings: SettingsStore = koinInject()
                    val passwords: SecurePasswordStore = koinInject()
                    SettingsScreen(
                        settingsStore = settings,
                        passwordStore = passwords,
                        onDone = { nav.popBackStack() },
                        onOpenDrawer = { openDrawer() }
                    )
                }

                composable(
                    route = "${Routes.PLAYER}/{channelId}/{serviceId}/{channelName}",
                    arguments = listOf(
                        navArgument("channelId") { type = NavType.IntType },
                        navArgument("serviceId") { type = NavType.IntType },
                        navArgument("channelName") { type = NavType.StringType },
                    )
                ) { backStackEntry ->
                    val channelId = backStackEntry.arguments?.getInt("channelId") ?: 0
                    val serviceId = backStackEntry.arguments?.getInt("serviceId") ?: 0
                    val channelName = backStackEntry.arguments?.getString("channelName") ?: ""

                    VideoPlayerScreen(
                        channelId = channelId,
                        channelName = channelName,
                        serviceId = serviceId,
                        onClose = { nav.popBackStack() }
                    )
                }
            }

            InfoBanner(message = status, modifier = Modifier.fillMaxSize())
        }
    }
}