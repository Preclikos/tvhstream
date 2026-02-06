package cz.preclikos.tvhstream.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage

@Composable
fun PiconBox(
    imageLoader: ImageLoader,
    baseUrl: String,
    piconPath: String?
) {
    val ctx = LocalContext.current

    val piconUrl = remember(baseUrl, piconPath) {
        if (baseUrl.isBlank() || piconPath.isNullOrBlank()) null
        else joinUrl(baseUrl, piconPath)
    }

    Box(
        modifier = Modifier
            .width(92.dp)
            .height(64.dp),
        contentAlignment = Alignment.Center
    ) {
        if (piconUrl == null) {
            Text("📺", style = MaterialTheme.typography.displayMedium)
        } else {
            SubcomposeAsyncImage(
                model = piconUrl,
                imageLoader = imageLoader,
                contentDescription = null,
                loading = {
                    Text("📺", style = MaterialTheme.typography.displayMedium)
                },
                error = {
                    Text("📺", style = MaterialTheme.typography.displayMedium)
                }
            )
        }
    }
}

private fun joinUrl(base: String, path: String): String =
    base.trimEnd('/') + "/" + path.trimStart('/')