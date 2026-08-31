package ch.frostnova.cli.idx.sync.ui

import java.util.Locale
import kotlin.math.abs

/** Human-readable binary byte size, e.g. `1.5 MB`. */
fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = 0
    while (abs(value) >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

/** Human-readable duration, e.g. `1m 05s` or `2.3s`. */
fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    return when {
        seconds < 10 -> String.format(Locale.ROOT, "%.1fs", seconds)
        total < 60 -> "${total}s"
        total < 3600 -> String.format(Locale.ROOT, "%dm %02ds", total / 60, total % 60)
        else -> String.format(Locale.ROOT, "%dh %02dm", total / 3600, (total % 3600) / 60)
    }
}
