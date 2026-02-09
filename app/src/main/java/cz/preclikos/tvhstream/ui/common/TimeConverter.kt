package cz.preclikos.tvhstream.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

val clockFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

fun formatClock(epochSec: Long): String {
    val z = ZoneId.systemDefault()
    return Instant.ofEpochSecond(epochSec).atZone(z).format(clockFormatter)
}

fun formatHms(sec: Long): String {
    val s = sec.coerceAtLeast(0L)
    val h = (s / 3600).toInt()
    val m = ((s % 3600) / 60).toInt()
    val ss = (s % 60).toInt()
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}

fun floorToMinutes(unixSec: Long, minutes: Int): Long {
    val step = minutes * 60L
    return (unixSec / step) * step
}

fun formatHm(unixSec: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = unixSec * 1000L }
    val h = cal.get(Calendar.HOUR_OF_DAY)
    val m = cal.get(Calendar.MINUTE)
    return "%02d:%02d".format(h, m)
}