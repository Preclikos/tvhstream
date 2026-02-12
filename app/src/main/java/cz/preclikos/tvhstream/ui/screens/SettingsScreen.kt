package cz.preclikos.tvhstream.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cz.preclikos.tvhstream.ui.Routes
import cz.preclikos.tvhstream.ui.components.SettingsSubRail
import cz.preclikos.tvhstream.ui.screens.settings.SettingsConnection
import cz.preclikos.tvhstream.ui.screens.settings.SettingsPlayer

object SettingsRoutes {
    const val GENERAL = "settings/general"
    const val PLAYER = "settings/player"
    const val CONNECTION = "settings/connection"
    const val ABOUT = "settings/about"
}

@Composable
fun SettingsScreen() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val activity = context as? Activity

    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topRoute = currentRoute?.substringBefore("/")

    BackHandler {
        when (currentRoute) {
            Routes.CHANNELS, Routes.EPG -> {
                activity?.finishAffinity()
                kotlin.system.exitProcess(0)
            }

            Routes.SETTINGS -> {
                nav.navigate(Routes.CHANNELS) { launchSingleTop = true }
            }

            else -> nav.popBackStack()
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(Modifier.fillMaxSize()) {
            SettingsSubRail(
                currentRoute = topRoute,
                onNavigate = { route ->
                    nav.navigate(route) {
                        popUpTo(Routes.CHANNELS) { inclusive = false }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                NavHost(
                    navController = nav,
                    startDestination = SettingsRoutes.CONNECTION,
                ) {
                    composable(SettingsRoutes.CONNECTION) {
                        SettingsConnection()
                    }

                    composable(SettingsRoutes.PLAYER) {
                        SettingsPlayer()
                    }
                }
            }
        }
    }
}