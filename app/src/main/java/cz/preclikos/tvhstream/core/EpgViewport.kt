package cz.preclikos.tvhstream.core

import cz.preclikos.tvhstream.htsp.EpgEventEntry
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry for the EPG grid's horizontal timeline, kept out of Compose so it is
 * JVM-testable. All x values are pixels from the start of the timeline content
 * (i.e. the same coordinate space as the horizontal ScrollState value).
 */
object EpgViewport {

    /**
     * Quantizes the scroll position into buckets of [stepPx], so the set of composed
     * programmes only changes when the viewport crosses a step boundary and not on
     * every frame of a scroll animation.
     */
    fun bucket(scrollPx: Int, stepPx: Float): Int {
        if (stepPx <= 0f) return 0
        return floor(max(0, scrollPx) / stepPx).toInt()
    }

    /**
     * Pixel range that must be composed for [bucket]: the bucket itself, the viewport
     * width, plus [marginSteps] steps on each side so a scroll step reveals content
     * that is already composed.
     */
    fun composeRangePx(
        bucket: Int,
        stepPx: Float,
        viewportPx: Int,
        marginSteps: Int = 1,
    ): ClosedFloatingPointRange<Float> {
        val start = max(0f, (bucket - marginSteps) * stepPx)
        val end = (bucket + 1 + marginSteps) * stepPx + max(0, viewportPx)
        return start..end
    }

    /**
     * Events that overlap both the timeline window and the pixel range [rangePx],
     * sorted by start. [pxPerSec] maps seconds since [windowStartSec] to pixels.
     */
    fun eventsInRange(
        epg: List<EpgEventEntry>,
        windowStartSec: Long,
        windowEndSec: Long,
        pxPerSec: Float,
        rangePx: ClosedFloatingPointRange<Float>,
    ): List<EpgEventEntry> =
        epg.asSequence()
            .filter { it.stop > windowStartSec && it.start < windowEndSec }
            .filter { e ->
                val startPx = (max(e.start, windowStartSec) - windowStartSec) * pxPerSec
                val endPx = (min(e.stop, windowEndSec) - windowStartSec) * pxPerSec
                endPx > rangePx.start && startPx < rangePx.endInclusive
            }
            .sortedBy { it.start }
            .toList()

    /**
     * x of the "now" line in timeline content coordinates, or null when now is
     * outside the window.
     */
    fun nowLineXPx(nowSec: Long, windowStartSec: Long, windowEndSec: Long, pxPerSec: Float): Float? {
        if (nowSec < windowStartSec || nowSec > windowEndSec) return null
        return (nowSec - windowStartSec) * pxPerSec
    }

    /**
     * How far to shift a programme title right so it stays readable at the left edge
     * after the programme's start has scrolled out of view. Never shifts further than
     * leaves [minTitlePx] of the cell for the title.
     *
     * @param titleStartPx where the title normally starts (content coordinates)
     * @param titleWidthPx width available for the title inside the cell
     */
    fun stickyTitleOffsetPx(scrollPx: Int, titleStartPx: Int, titleWidthPx: Int, minTitlePx: Int): Int {
        val maxShift = max(0, titleWidthPx - minTitlePx)
        return (scrollPx - titleStartPx).coerceIn(0, maxShift)
    }
}
