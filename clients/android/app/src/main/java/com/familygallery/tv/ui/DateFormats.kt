package com.familygallery.tv.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Date/duration formatting, using java.util so it works on every supported API level. */
object DateFormats {

    /**
     * Month header for the timeline feed, e.g. "2025, июль" (year-first, lowercase standalone
     * month). Year-first reads unambiguously and avoids the wrong genitive Russian month form.
     */
    fun monthLabel(epochMs: Long): String {
        val d = Date(epochMs)
        val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(d)
        val month = standaloneMonth(d).replaceFirstChar { it.lowercase(Locale.getDefault()) }
        return "$year, $month"
    }

    /** Capitalized standalone month name for a 1-based month, e.g. 7 → "Июль". */
    fun monthName(month1: Int): String {
        val cal = Calendar.getInstance().apply { set(Calendar.MONTH, (month1 - 1).coerceIn(0, 11)) }
        return standaloneMonth(cal.time).replaceFirstChar { it.uppercase(Locale.getDefault()) }
    }

    /** Screen title for a specific month, e.g. "2025, июль". */
    fun yearMonthLabel(year: Int, month1: Int): String =
        "$year, ${monthName(month1).lowercase(Locale.getDefault())}"

    /** Full timestamp for the viewer overlay, e.g. "14 июля 2023, 10:30". */
    fun fullDateLabel(epochMs: Long): String =
        SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMs))

    /**
     * Today's "MM-dd" in the device's default timezone — the key for the "On this day" album.
     * Locale.US pins ASCII digits and zero-padding so it matches SQLite's strftime('%m-%d') output.
     */
    fun todayMonthDay(): String = SimpleDateFormat("MM-dd", Locale.US).format(Date())

    /** Video duration as m:ss or h:mm:ss. */
    fun durationLabel(ms: Long?): String {
        val total = ((ms ?: 0L) / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    // "LLLL" is the standalone month form (nominative in Russian) rather than the genitive
    // form "MMMM" produces inside a full date.
    private fun standaloneMonth(date: Date): String =
        SimpleDateFormat("LLLL", Locale.getDefault()).format(date)
}
