package com.familygallery.tv.data

/**
 * Meteorological seasons, keyed by capture month. The single source of truth for the month↔season
 * mapping shared by the SQL the catalog runs and the labels the UI shows. Codes are 0..3 so they
 * survive back-stack save/restore as a plain Int and sort in calendar order (winter→autumn).
 */
object Seasons {
    const val WINTER = 0
    const val SPRING = 1
    const val SUMMER = 2
    const val AUTUMN = 3

    /** All season codes in display order. */
    val ALL = intArrayOf(WINTER, SPRING, SUMMER, AUTUMN)

    /** 1-based months belonging to [season] (meteorological: winter = Dec/Jan/Feb, …). */
    fun monthsOf(season: Int): IntArray = when (season) {
        WINTER -> intArrayOf(12, 1, 2)
        SPRING -> intArrayOf(3, 4, 5)
        SUMMER -> intArrayOf(6, 7, 8)
        else -> intArrayOf(9, 10, 11)
    }

    /** Season code (0..3) for a 1-based [month]. */
    fun ofMonth(month: Int): Int = when (month) {
        12, 1, 2 -> WINTER
        3, 4, 5 -> SPRING
        6, 7, 8 -> SUMMER
        else -> AUTUMN
    }
}
