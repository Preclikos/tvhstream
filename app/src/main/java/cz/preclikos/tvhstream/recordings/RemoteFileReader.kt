package cz.preclikos.tvhstream.recordings

import cz.preclikos.tvhstream.htsp.HtspService

/** A file on the server, read through HTSP (fileOpen/fileSeek/fileRead/fileClose). */
interface RemoteFile {
    /** Opens the file; returns its size in bytes if the server reports it. */
    suspend fun open(): Long?

    /** Moves the server-side position; returns the new absolute offset. */
    suspend fun seek(offset: Long): Long

    /** Reads up to [maxBytes] from the current position; empty = end of file. */
    suspend fun read(maxBytes: Int): ByteArray

    suspend fun close()
}

class HtspRemoteFile(
    private val htsp: HtspService,
    private val path: String,
    /** Seeks reopen the file; only the first open of a playback should count as a play. */
    private val keepPlaycount: Boolean = false,
) : RemoteFile {
    private var id: Int? = null

    override suspend fun open(): Long? {
        val (fileId, size) = htsp.fileOpenWithSize(path)
        id = fileId
        return size
    }

    override suspend fun seek(offset: Long): Long = htsp.fileSeek(requireId(), offset)

    override suspend fun read(maxBytes: Int): ByteArray = htsp.fileRead(requireId(), maxBytes)

    override suspend fun close() {
        val fileId = id ?: return
        id = null
        htsp.fileClose(fileId, keepPlaycount = keepPlaycount)
    }

    private fun requireId() = id ?: throw IllegalStateException("File $path is not open")
}

/**
 * Sequential reader over a [RemoteFile] that fetches [chunkSize] bytes per round trip
 * and serves the caller's (usually small) reads from that buffer.
 */
class ChunkedRemoteReader(
    private val file: RemoteFile,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    private var buffer = ByteArray(0)
    private var bufferPos = 0
    private var eof = false

    /** Absolute position of the next byte returned by [read]. */
    var position: Long = 0L
        private set

    /** Opens the file at [startPosition]; returns the file size if known. */
    suspend fun open(startPosition: Long): Long? {
        val size = file.open()
        if (startPosition > 0) position = file.seek(startPosition)
        return size
    }

    /** Reads into [target]; returns the number of bytes read or -1 at end of file. */
    suspend fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bufferPos >= buffer.size) {
            if (eof) return -1
            val chunk = file.read(chunkSize)
            if (chunk.isEmpty()) {
                eof = true
                return -1
            }
            buffer = chunk
            bufferPos = 0
        }
        val n = minOf(length, buffer.size - bufferPos)
        System.arraycopy(buffer, bufferPos, target, offset, n)
        bufferPos += n
        position += n
        return n
    }

    suspend fun close() {
        buffer = ByteArray(0)
        bufferPos = 0
        file.close()
    }

    companion object {
        // One HTSP round trip per chunk; well below the 32 MB message cap.
        const val DEFAULT_CHUNK_SIZE = 256 * 1024
    }
}
