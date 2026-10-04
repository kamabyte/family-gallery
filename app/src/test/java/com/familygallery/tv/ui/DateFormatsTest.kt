package com.familygallery.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DateFormatsTest {

    /**
     * The "On this day" key must match SQLite's `strftime('%m-%d', ...)`: zero-padded, ASCII,
     * MM-dd. A drift here silently makes the album empty, so pin the exact shape.
     */
    @Test fun todayMonthDayIsZeroPaddedMonthDay() {
        val s = DateFormats.todayMonthDay()
        assertTrue("expected MM-dd, got $s", s.matches(Regex("""\d{2}-\d{2}""")))

        val cal = Calendar.getInstance(TimeZone.getDefault())
        val expected = String.format(
            "%02d-%02d",
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
        assertEquals(expected, s)
    }
}
