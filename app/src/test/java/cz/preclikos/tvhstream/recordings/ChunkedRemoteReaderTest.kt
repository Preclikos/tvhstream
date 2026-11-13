package cz.preclikos.tvhstream.recordings

import androidx.media3.common.C
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Fake HTSP file: behaves like htsp_method_file_read/seek on a byte array. */
private class FakeRemoteFile(private val data: ByteArray) : RemoteFile {
    var pos = 0
    val seeks = mutableListOf<Long>()
    val readSizes = mutableListOf<Int>()
    var opened = false
    var closed = false

    override suspend fun open(): Long? {
        opened = true
        return data.size.toLong()
    }

    override suspend fun seek(offset: Long): Long {
        seeks += offset
        pos = offset.toInt().coerceIn(0, data.size)
        return pos.toLong()
    }

    override suspend fun read(maxBytes: Int): ByteArray {
        readSizes += maxBytes
        val n = minOf(maxBytes, data.size - pos)
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }

    override suspend fun close() {
        closed = true
    }
}

class ChunkedRemoteReaderTest {

    private val data = ByteArray(10_000) { (it % 251).toByte() }

    private fun readAll(reader: ChunkedRemoteReader, step: Int): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(step)
        while (true) {
            val n = reader.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    @Test
    fun readsWholeFile_acrossChunkBoundaries_withoutSeeking() {
        val file = FakeRemoteFile(data)
        val reader = ChunkedRemoteReader(file, chunkSize = 4096)

        val size = runBlocking { reader.open(0) }

        assertEquals(10_000L, size)
        assertArrayEquals(data, readAll(reader, step = 1000)) // 1000 doesn't divide 4096
        assertTrue("no seek for position 0", file.seeks.isEmpty())
        // 4096 + 4096 + 1808, then one empty read = EOF
        assertEquals(listOf(4096, 4096, 4096, 4096), file.readSizes)
        assertEquals(10_000L, reader.position)
    }

    @Test
    fun openAtPosition_seeksOnce_andReturnsTheRest() {
        val file = FakeRemoteFile(data)
        val reader = ChunkedRemoteReader(file, chunkSize = 3000)

        runBlocking { reader.open(7_500) }

        assertEquals(listOf(7_500L), file.seeks)
        assertArrayEquals(data.copyOfRange(7_500, 10_000), readAll(reader, step = 777))
    }

    @Test
    fun endOfFile_staysEnd_andCloseClosesTheRemoteFile() = runBlocking {
        val file = FakeRemoteFile(ByteArray(10) { 1 })
        val reader = ChunkedRemoteReader(file, chunkSize = 64)
        reader.open(0)
        val buf = ByteArray(64)

        assertEquals(10, reader.read(buf, 0, 64))
        assertEquals(-1, reader.read(buf, 0, 64))
        assertEquals(-1, reader.read(buf, 0, 64))
        assertEquals(0, reader.read(buf, 0, 0))

        reader.close()
        assertTrue(file.closed)
    }

    @Test
    fun remainingBytes_prefersRequestedLength_thenKnownSize() {
        val unset = C.LENGTH_UNSET.toLong()
        assertEquals(500L, HtspFileDataSource.remainingBytes(size = 10_000, position = 0, length = 500))
        assertEquals(2_500L, HtspFileDataSource.remainingBytes(size = 10_000, position = 7_500, length = unset))
        assertEquals(0L, HtspFileDataSource.remainingBytes(size = 10_000, position = 12_000, length = unset))
        assertEquals(unset, HtspFileDataSource.remainingBytes(size = null, position = 0, length = unset))
    }
}
