package cz.preclikos.tvhstream.recordings

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import cz.preclikos.tvhstream.htsp.HtspService
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ExoPlayer DataSource for a recording file, read over HTSP only (fileOpen "dvr/<id>",
 * fileSeek, fileRead, fileClose) — never Tvheadend's HTTP /dvrfile.
 * Called on ExoPlayer's loader thread, so blocking on the HTSP requests is fine.
 */
@OptIn(UnstableApi::class)
class HtspFileDataSource(
    private val fileFactory: () -> RemoteFile,
) : BaseDataSource(/* isNetwork = */ true) {

    private var reader: ChunkedRemoteReader? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val r = ChunkedRemoteReader(fileFactory())
        val size = try {
            runBlocking { r.open(dataSpec.position) }
        } catch (t: Throwable) {
            runCatching { runBlocking { r.close() } }
            throw IOException("Cannot open recording ${dataSpec.uri}", t)
        }
        reader = r
        bytesRemaining = remainingBytes(size, dataSpec.position, dataSpec.length)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: throw IOException("Not opened")
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
        else minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            runBlocking { r.read(buffer, offset, toRead) }
        } catch (t: Throwable) {
            throw IOException("Reading recording failed", t)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val r = reader
        reader = null
        uri = null
        if (r != null) runCatching { runBlocking { r.close() } }
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(
        private val htsp: HtspService,
        private val recording: Recording,
    ) : DataSource.Factory {
        // ExoPlayer opens a new data source for every seek; let only the first one
        // count as a play on the server.
        private val counted = AtomicBoolean(false)

        override fun createDataSource(): DataSource {
            val keepPlaycount = counted.getAndSet(true)
            return HtspFileDataSource {
                HtspRemoteFile(htsp, recording.filePath, keepPlaycount = keepPlaycount)
            }
        }
    }

    companion object {
        /**
         * Bytes ExoPlayer may read from [position]: the requested [length] if given,
         * else what's left of a file of known [size], else unknown (LENGTH_UNSET).
         */
        fun remainingBytes(size: Long?, position: Long, length: Long): Long = when {
            length != C.LENGTH_UNSET.toLong() -> length
            size != null && size >= 0 -> (size - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
    }
}
