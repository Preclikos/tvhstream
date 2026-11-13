package cz.preclikos.tvhstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ConcurrentDrainTest {

    @Test
    fun drainsAllEntries_andEmptiesMap() {
        val map = ConcurrentHashMap(mapOf(1 to "a", 2 to "b", 3 to "c"))
        val seen = mutableListOf<String>()

        map.drainEach { seen += it }

        assertEquals(setOf("a", "b", "c"), seen.toSet())
        assertTrue(map.isEmpty())
    }

    @Test
    fun emptyMap_isNoOp() {
        val map = ConcurrentHashMap<Int, String>()
        map.drainEach { throw AssertionError("must not be called") }
        assertTrue(map.isEmpty())
    }

    /**
     * Regression for the Crashlytics NoSuchElementException in HtspService.failAll:
     * the reader thread removes entries while another thread fails them all.
     * Every value must be delivered exactly once, by one side or the other.
     */
    @Test
    fun concurrentRemoval_neverThrows_andDeliversEachValueOnce() {
        repeat(200) { round ->
            val map = ConcurrentHashMap<Int, Int>()
            (0 until 50).forEach { map[it] = it }
            val delivered = Collections.synchronizedList(mutableListOf<Int>())
            val error = AtomicReference<Throwable?>()
            val start = CountDownLatch(1)

            val remover = thread {
                start.await()
                for (k in 0 until 50) map.remove(k)?.let { delivered += it }
            }
            val drainer = thread {
                start.await()
                try {
                    map.drainEach { delivered += it }
                } catch (t: Throwable) {
                    error.set(t)
                }
            }
            start.countDown()
            remover.join()
            drainer.join()

            error.get()?.let { throw AssertionError("round $round threw", it) }
            assertEquals("round $round", (0 until 50).toList(), delivered.sorted())
            assertTrue(map.isEmpty())
        }
    }
}
