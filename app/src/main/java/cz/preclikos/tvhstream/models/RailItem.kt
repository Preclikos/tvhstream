package cz.preclikos.tvhstream.models

import androidx.compose.runtime.Composable

data class RailItem(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit
)