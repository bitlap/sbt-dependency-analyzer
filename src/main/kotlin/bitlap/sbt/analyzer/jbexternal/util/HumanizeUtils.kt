@file:Suppress("unused")

package bitlap.sbt.analyzer.jbexternal.util

import java.util.concurrent.TimeUnit
import kotlin.math.log2
import kotlin.math.pow

// Also see:
// https://developer.android.com/reference/android/text/format/DateUtils.html#formatElapsedTime(long)
/** Formats elapsed milliseconds as mm:ss or hh:mm:ss, discarding sub-second precision. */
val Long.formatMsAsDuration: String
    get() {
        fun normalize(number: Long): String = String.format("%02d", number)

        val seconds = TimeUnit.MILLISECONDS.toSeconds(this) % 60
        val minutes = TimeUnit.MILLISECONDS.toMinutes(this) % 60
        return when (val hours = TimeUnit.MILLISECONDS.toHours(this)) {
            0L -> "${normalize(minutes)}:${normalize(seconds)}"
            else -> "${normalize(hours)}:${normalize(minutes)}:${normalize(seconds)}"
        }
    }

/** Formats a non-negative byte count using the Long binary-size formatter. */
val Int.formatAsFileSize: String
    get() = toLong().formatAsFileSize

/** Formats a non-negative byte count in powers of 1024 with magnitude-dependent decimal precision. */
val Long.formatAsFileSize: String
    get() = log2(if (this != 0L) toDouble() else 1.0).toInt().div(10).let {
        val precision = when (it) {
            0 -> 0; 1 -> 1; else -> 2
        }
        val prefix = arrayOf("", "K", "M", "G", "T", "P", "E", "Z", "Y")
        String.format("%.${precision}f ${prefix[it]}B", toDouble() / 2.0.pow(it * 10.0))
    }
