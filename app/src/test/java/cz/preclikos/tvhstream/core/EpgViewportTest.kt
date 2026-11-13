package cz.preclikos.tvhstream.core

import cz.preclikos.tvhstream.htsp.EpgEventEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgViewportTest {

    private fun ev(id: Int, startMin: Long, stopMin: Long) =
        EpgEventEntry(eventId = id, channelId = 1, start = startMin * 60, stop = stopMin * 60, title = "e$id")

    // 1 px per second keeps the arithmetic readable: 1 minute = 60 px.
    private val pxPerSec = 1f

    @Test
    fun bucket_quantizesScrollIntoSteps() {
        assertEquals(0, EpgViewport.bucket(0, 100f))
        assertEquals(0, EpgViewport.bucket(99, 100f))
        assertEquals(1, EpgViewport.bucket(100, 100f))
        assertEquals(3, EpgViewport.bucket(350, 100f))
        assertEquals(0, EpgViewport.bucket(-20, 100f))
        assertEquals(0, EpgViewport.bucket(500, 0f))
    }

    @Test
    fun bucket_isStableWithinAStep_soScrollFramesDoNotRecompose() {
        val buckets = (200 until 300 step 7).map { EpgViewport.bucket(it, 100f) }.toSet()
        assertEquals(setOf(2), buckets)
    }

    @Test
    fun composeRange_coversViewportPlusMarginOnBothSides() {
        val r = EpgViewport.composeRangePx(bucket = 3, stepPx = 100f, viewportPx = 1000, marginSteps = 1)
        assertEquals(200f, r.start)
        assertEquals(500f + 1000f, r.endInclusive)
    }

    @Test
    fun composeRange_neverStartsBeforeZero() {
        val r = EpgViewport.composeRangePx(bucket = 0, stepPx = 100f, viewportPx = 500)
        assertEquals(0f, r.start)
    }

    @Test
    fun eventsInRange_dropsEventsOutsideTheVisibleRange_andSorts() {
        val epg = listOf(
            ev(3, 120, 180), // 7200..10800 px -> outside
            ev(2, 30, 60),   // 1800..3600 px -> inside
            ev(1, 0, 30),    // 0..1800 px -> inside
            ev(4, -60, -10), // before the window
        )
        val visible = EpgViewport.eventsInRange(
            epg, windowStartSec = 0, windowEndSec = 24 * 3600, pxPerSec = pxPerSec,
            rangePx = 0f..4000f,
        )
        assertEquals(listOf(1, 2), visible.map { it.eventId })
    }

    @Test
    fun eventsInRange_keepsLongProgrammeThatStartedBeforeTheRange() {
        val epg = listOf(ev(1, 0, 600)) // 0..36000 px, viewport in the middle
        val visible = EpgViewport.eventsInRange(epg, 0, 24 * 3600, pxPerSec, 10000f..12000f)
        assertEquals(listOf(1), visible.map { it.eventId })
    }

    @Test
    fun eventsInRange_clipsToWindow() {
        // Ends exactly at the window start -> not visible.
        val epg = listOf(ev(1, -30, 0), ev(2, 0, 30))
        val visible = EpgViewport.eventsInRange(epg, 0, 3600, pxPerSec, 0f..100000f)
        assertEquals(listOf(2), visible.map { it.eventId })
    }

    @Test
    fun nowLine_insideAndOutsideWindow() {
        assertEquals(600f, EpgViewport.nowLineXPx(nowSec = 600, windowStartSec = 0, windowEndSec = 3600, pxPerSec = 1f))
        assertNull(EpgViewport.nowLineXPx(nowSec = -1, windowStartSec = 0, windowEndSec = 3600, pxPerSec = 1f))
        assertNull(EpgViewport.nowLineXPx(nowSec = 3601, windowStartSec = 0, windowEndSec = 3600, pxPerSec = 1f))
    }

    @Test
    fun stickyTitle_followsLeftEdge_withinTheCell() {
        // Title normally at 1000 px, 600 px wide, keep at least 150 px of it.
        assertEquals(0, EpgViewport.stickyTitleOffsetPx(scrollPx = 500, titleStartPx = 1000, titleWidthPx = 600, minTitlePx = 150))
        assertEquals(200, EpgViewport.stickyTitleOffsetPx(scrollPx = 1200, titleStartPx = 1000, titleWidthPx = 600, minTitlePx = 150))
        assertEquals(450, EpgViewport.stickyTitleOffsetPx(scrollPx = 5000, titleStartPx = 1000, titleWidthPx = 600, minTitlePx = 150))
    }

    @Test
    fun stickyTitle_narrowCellNeverShifts() {
        assertEquals(0, EpgViewport.stickyTitleOffsetPx(scrollPx = 5000, titleStartPx = 1000, titleWidthPx = 100, minTitlePx = 150))
    }
}
