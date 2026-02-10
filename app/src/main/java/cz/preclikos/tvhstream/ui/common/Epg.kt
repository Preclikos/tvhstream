package cz.preclikos.tvhstream.ui.common

import cz.preclikos.tvhstream.htsp.EpgEventEntry
import kotlin.math.abs

fun List<EpgEventEntry>.nowEvent(nowSec: Long): EpgEventEntry? =
    firstOrNull { it.start <= nowSec && nowSec < it.stop }
        ?: minByOrNull { abs(it.start - nowSec) }

fun List<EpgEventEntry>.nextAfter(now: EpgEventEntry?): EpgEventEntry? {
    val nowId = now?.eventId ?: return firstOrNull()
    val idx = indexOfFirst { it.eventId == nowId }
    return if (idx >= 0 && idx + 1 < size) this[idx + 1] else null
}

fun EpgEventEntry.progress(nowSec: Long): Float {
    val dur = (stop - start).coerceAtLeast(1L)
    val pos = (nowSec - start).coerceIn(0L, dur)
    return (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
}