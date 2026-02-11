package cz.preclikos.tvhstream.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.request.Options
import cz.preclikos.tvhstream.htsp.ConnectionState
import cz.preclikos.tvhstream.htsp.HtspService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Buffer
import okio.FileSystem

data class HtspPiconData(
    val serverTag: String,
    val path: String,
    val ttlMs: Long
)

@Composable
fun PiconBox(
    imageLoader: ImageLoader,
    serverTag: String = "default",
    piconPath: String?
) {
    val piconUrl = remember(serverTag, piconPath) {
        if (serverTag.isBlank() || piconPath.isNullOrBlank()) null
        else toHtspPiconUrl(serverTag, piconPath)
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
                loading = { Text("📺", style = MaterialTheme.typography.displayMedium) },
                error = { Text("📺", style = MaterialTheme.typography.displayMedium) }
            )
        }
    }
}

private fun toHtspPiconUrl(serverTag: String, piconPath: String): String {
    val p = piconPath.trimStart('/')
    return "htsp-picon://$serverTag/$p"
}

class HtspPiconMapper(
    private val defaultTtlMs: Long = 6 * 60 * 60 * 1000L
) : Mapper<String, HtspPiconData> {

    override fun map(data: String, options: Options): HtspPiconData? {
        if (!data.startsWith("htsp-picon://")) return null

        val noScheme = data.removePrefix("htsp-picon://")
        val slash = noScheme.indexOf('/')
        if (slash <= 0) return null

        val tag = noScheme.substring(0, slash)
        val path = noScheme.substring(slash + 1)

        return HtspPiconData(
            serverTag = tag,
            path = path,
            ttlMs = defaultTtlMs
        )
    }
}

class HtspPiconKeyer : Keyer<HtspPiconData> {
    override fun key(data: HtspPiconData, options: Options): String {
        val p = data.path.let { if (it.startsWith("/")) it else "/$it" }
        val bucket = if (data.ttlMs > 0) (System.currentTimeMillis() / data.ttlMs) else 0L
        return "htsp-picon:${data.serverTag}:$p:bucket=$bucket"
    }
}

class HtspPiconFetcher(
    private val htsp: HtspService,
    private val data: HtspPiconData
) : Fetcher {

    override suspend fun fetch(): FetchResult = piconSemaphore.withPermit {
        val connected = ensureConnectedOrWait(maxWaitMs = 800)
        if (!connected) {
            throw IllegalStateException("HTSP not connected")
        }

        val path = data.path.let { if (it.startsWith("/")) it else "/$it" }

        val handle = htsp.fileOpen(path, timeoutMs = 3_000)
        try {
            val buf = Buffer()

            while (currentCoroutineContext().isActive) {
                val chunk = htsp.fileRead(handle, size = 64 * 1024, timeoutMs = 3_000)
                if (chunk.isEmpty()) break
                buf.write(chunk)
            }

            SourceFetchResult(
                source = ImageSource(
                    source = buf,
                    fileSystem = FileSystem.SYSTEM
                ),
                mimeType = null,
                dataSource = DataSource.NETWORK
            )
        } finally {
            runCatching { htsp.fileClose(handle, timeoutMs = 1_500) }
        }
    }

    private suspend fun ensureConnectedOrWait(maxWaitMs: Long): Boolean {
        if (htsp.state.value is ConnectionState.Connected) return true

        val step = 100L
        var waited = 0L
        while (waited < maxWaitMs && currentCoroutineContext().isActive) {
            delay(step)
            waited += step
            if (htsp.state.value is ConnectionState.Connected) return true
        }
        return htsp.state.value is ConnectionState.Connected
    }

    class Factory(private val htsp: HtspService) : Fetcher.Factory<HtspPiconData> {
        override fun create(
            data: HtspPiconData,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher {
            return HtspPiconFetcher(htsp, data)
        }
    }

    companion object {
        private val piconSemaphore = Semaphore(permits = 3)
    }
}

fun buildImageLoader(context: Context, htsp: HtspService): ImageLoader {
    return ImageLoader.Builder(context)
        .components {
            add(HtspPiconMapper(defaultTtlMs = 6 * 60 * 60 * 1000L))
            add(HtspPiconKeyer())
            add(HtspPiconFetcher.Factory(htsp))
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("coil_disk_cache"))
                .maxSizeBytes(128L * 1024 * 1024)
                .build()
        }
        .build()
}