package cz.preclikos.tvhstream.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cz.preclikos.tvhstream.settings.SecurePasswordStore
import cz.preclikos.tvhstream.settings.SettingsStore
import cz.preclikos.tvhstream.ui.player.VideoPlayerScreen
import cz.preclikos.tvhstream.viewmodels.AppConnectionViewModel
import cz.preclikos.tvhstream.viewmodels.VideoPlayerViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

object Routes {
    const val CHANNELS = "channels"
    const val SETTINGS = "settings"
    const val PLAYER = "player"
    fun player(channelId: Int, serviceId: Int, channelName: String) =
        "player/$channelId/$serviceId/${android.net.Uri.encode(channelName)}"
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()

    val appVm: AppConnectionViewModel = koinViewModel()
    val status by appVm.status.collectAsState()

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = Routes.CHANNELS
        ) {
            composable(Routes.CHANNELS) {
                ChannelsScreen(
                    vm = appVm,
                    onPlay = { channelId, serviceId, name ->
                        nav.navigate(Routes.player(channelId, serviceId, name))
                    },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) }
                )
            }

            composable(Routes.SETTINGS) {
                val settings: SettingsStore = koinInject()
                val passwords: SecurePasswordStore = koinInject()

                SettingsScreen(
                    settingsStore = settings,
                    passwordStore = passwords,
                    onDone = { nav.popBackStack() }
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
                val playerVm: VideoPlayerViewModel = koinViewModel()

                val channelId = backStackEntry.arguments?.getInt("channelId") ?: 0
                val serviceId = backStackEntry.arguments?.getInt("serviceId") ?: 0
                val channelName = backStackEntry.arguments?.getString("channelName") ?: ""

                VideoPlayerScreen(
                    vm = playerVm,
                    channelId = channelId,
                    channelName = channelName,
                    serviceId = serviceId,
                    onClose = { nav.popBackStack() }
                )
            }
        }

        InfoBanner(
            message = status,
            modifier = Modifier.fillMaxSize()
        )
    }
}