package cz.preclikos.tvhstream.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun InfoBanner(
    message: String,
    modifier: Modifier = Modifier,
    visibleMs: Long = 2500L,
) {
    var visible by remember { mutableStateOf(false) }
    var lastMessage by remember { mutableStateOf("") }

    LaunchedEffect(message) {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || trimmed == lastMessage) return@LaunchedEffect

        lastMessage = trimmed
        visible = true
        delay(visibleMs)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = tween(220)
        ) + fadeIn(animationSpec = tween(220)),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = tween(220)
        ) + fadeOut(animationSpec = tween(220)),
        modifier = modifier
    ) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .widthIn(max = 720.dp)
            ) {
                Text(
                    text = lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}