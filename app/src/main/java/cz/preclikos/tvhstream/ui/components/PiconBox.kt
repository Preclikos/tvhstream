package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage

data class HtspPiconData(
    val serverTag: String,
    val path: String,
    val ttlMs: Long
)

@Composable
fun PiconBox(
    imageLoader: ImageLoader,
    serverTag: String = "default",
    piconPath: String?,
    modifier: Modifier = Modifier
        .width(92.dp)
        .height(64.dp),
) {
    val piconUrl = remember(serverTag, piconPath) {
        cz.preclikos.tvhstream.core.resolvePiconModel(serverTag, piconPath)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (piconUrl == null) {
            PiconPlaceholder()
        } else {
            SubcomposeAsyncImage(
                model = piconUrl,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                loading = { PiconPlaceholder() },
                error = { PiconPlaceholder() }
            )
        }
    }
}

@Composable
private fun PiconPlaceholder() {
    Icon(
        imageVector = Icons.Outlined.LiveTv,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxSize(0.5f),
    )
}
