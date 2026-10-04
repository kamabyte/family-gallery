package com.familygallery.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonsTest {

    /** monthsOf and ofMonth must be exact inverses so the SQL bucketing and UI labels agree. */
    @Test fun monthsOfAndOfMonthAreConsistent() {
        for (season in Seasons.ALL) {
            for (month in Seasons.monthsOf(season)) {
                assertEquals("month $month -> season", season, Seasons.ofMonth(month))
            }
        }
    }

    /** Every calendar month belongs to exactly one season. */
    @Test fun allTwelveMonthsAreCoveredOnce() {
        val covered = Seasons.ALL.flatMap { Seasons.monthsOf(it).toList() }
        assertEquals(12, covered.size)
        assertEquals((1..12).toSet(), covered.toSet())
    }

    @Test fun boundariesLandInTheExpectedSeason() {
        assertEquals(Seasons.WINTER, Seasons.ofMonth(12))
        assertEquals(Seasons.WINTER, Seasons.ofMonth(2))
        assertEquals(Seasons.SPRING, Seasons.ofMonth(3))
        assertEquals(Seasons.SUMMER, Seasons.ofMonth(6))
        assertEquals(Seasons.AUTUMN, Seasons.ofMonth(9))
        assertEquals(Seasons.AUTUMN, Seasons.ofMonth(11))
    }
}
