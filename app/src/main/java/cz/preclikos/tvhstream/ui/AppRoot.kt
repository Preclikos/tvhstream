package cz.preclikos.tvhstream.ui

import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import cz.preclikos.tvhstream.ui.recordings.RecordingsScreen
import cz.preclikos.tvhstream.ui.recordings.RecordingPlayerScreen
import cz.preclikos.tvhstream.ui.components.CertificateTrustDialog
import androidx.compose.foundation.layout.padding
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cz.preclikos.tvhstream.ui.components.ContentContainer
import cz.preclikos.tvhstream.ui.components.InfoBanner
import cz.preclikos.tvhstream.ui.components.SideRail
import cz.preclikos.tvhstream.ui.player.VideoPlayerScreen
import cz.preclikos.tvhstream.ui.screens.ChannelsScreen
import cz.preclikos.tvhstream.ui.screens.EpgGridScreen
import cz.preclikos.tvhstream.ui.screens.SettingsScreen
import cz.preclikos.tvhstream.viewmodels.AppConnectionViewModel
import org.koin.androidx.compose.koinViewModel

object Routes {
    const val CHANNELS = "channels"
    const val EPG = "epg"
    const val SETTINGS = "settings"
    const val PLAYER = "player"
    const val RECORDINGS = "recordings"
    const val RECORDING = "recording"
    fun recording(id: Int) = "recording/$id"
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

    val backStackEntry by nav.currentBackStackEntryAsState()

    val contentFocus = remember { FocusRequester() }
    val currentRoute = backStackEntry?.destination?.route
    val topRoute = currentRoute?.substringBefore("/")
    val showRail = topRoute != Routes.PLAYER && topRoute != Routes.RECORDING

    val isPlayer = currentRoute?.startsWith(Routes.PLAYER) == true

    BackHandler {
        when (currentRoute) {
            Routes.CHANNELS, Routes.EPG -> {
                // Close every activity in the task (not exitProcess, which skipped the
                // lifecycle); AppConnectionViewModel.onCleared tears down HTSP + EPG.
                activity?.finishAffinity()
            }

            Routes.SETTINGS -> {
                nav.navigate(Routes.CHANNELS) { launchSingleTop = true }
            }

            else -> nav.popBackStack()
        }
    }
    val certPrompt by appVm.certificatePrompt.collectAsState()
    certPrompt?.let { prompt ->
        CertificateTrustDialog(
            host = prompt.host,
            fingerprint = prompt.fingerprint,
            previousFingerprint = prompt.previous,
            onTrust = { appVm.trustCertificate(prompt) },
            onReject = { appVm.rejectCertificate() },
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // Compose maps Back to FocusDirection.Exit: with focus inside the rail or the
            // content focus group, the first Back only left the group and was consumed,
            // so exiting took two presses. On the home screens, handle Back here first.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Back) return@onPreviewKeyEvent false
                if (currentRoute != Routes.CHANNELS && currentRoute != Routes.EPG) {
                    return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyUp) activity?.finishAffinity()
                true
            },
        color = MaterialTheme.colorScheme.background
    ) {
        Row(Modifier.fillMaxSize()) {
            if (showRail) {
                SideRail(
                    currentRoute = topRoute,
                    onNavigate = { route ->
                        val current = nav.currentBackStackEntry?.destination?.route
                        if (current == route) {
                            if (!isPlayer) contentFocus.requestFocus()
                        } else {
                            nav.navigate(route) {
                                popUpTo(Routes.CHANNELS) { inclusive = false }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                NavHost(
                    navController = nav,
                    startDestination = Routes.CHANNELS,
                    // Short linear crossfade: rail navigation feels instant on TV
                    // (the Navigation default is a ~700 ms fade).
                    enterTransition = { fadeIn(tween(NAV_FADE_MS, easing = LinearEasing)) },
                    exitTransition = { fadeOut(tween(NAV_FADE_MS, easing = LinearEasing)) },
                    popEnterTransition = { fadeIn(tween(NAV_FADE_MS, easing = LinearEasing)) },
                    popExitTransition = { fadeOut(tween(NAV_FADE_MS, easing = LinearEasing)) },
                ) {

                    composable(Routes.CHANNELS) {
                        ContentContainer(contentFocus) {
                            ChannelsScreen(
                                onOpenSettings = {
                                    nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
                                },
                                onPlay = { channelId, serviceId, name ->
                                    nav.navigate(Routes.player(channelId, serviceId, name))
                                }
                            )
                        }
                    }

                    composable(Routes.EPG) {
                        ContentContainer(contentFocus) {
                            EpgGridScreen(
                                onOpenSettings = {
                                    nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
                                },
                                onPlay = { channelId, serviceId, name ->
                                    nav.navigate(Routes.player(channelId, serviceId, name))
                                }
                            )
                        }
                    }

                    composable(Routes.RECORDINGS) {
                        ContentContainer(contentFocus) {
                            RecordingsScreen(onPlay = { nav.navigate(Routes.recording(it.id)) })
                        }
                    }

                    composable(
                        route = "${Routes.RECORDING}/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.IntType }),
                    ) { entry ->
                        RecordingPlayerScreen(recordingId = entry.arguments?.getInt("id") ?: -1)
                    }

                    composable(Routes.SETTINGS) {
                        ContentContainer(contentFocus) {
                            SettingsScreen()
                        }
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
}

private const val NAV_FADE_MS = 150
