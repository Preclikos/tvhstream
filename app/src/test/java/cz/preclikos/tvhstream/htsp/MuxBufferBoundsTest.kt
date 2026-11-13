package cz.preclikos.tvhstream.htsp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each buffered muxpkt holds a whole video frame (tens of KB). 8192 of them OOM'd
 * 128 MB-heap TVs (Crashlytics, 0.0.38). Keep the hand-off buffer small; the data
 * source's ring buffer is the real jitter buffer.
 */
class MuxBufferBoundsTest {
    @Test
    fun muxEventBuffer_staysSmall() {
        assertTrue(HtspService.MUX_EVENT_BUFFER_CAPACITY in 64..1024)
    }
}
