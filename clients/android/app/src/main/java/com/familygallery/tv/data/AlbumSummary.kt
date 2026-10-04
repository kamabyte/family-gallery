package com.familygallery.tv.data

/** An album plus its cover thumbnail path — everything the Albums tab needs to draw a card. */
data class AlbumSummary(
    val id: Long,
    val name: String,
    val type: String,          // "place" | "camera" | "year"
    val photoCount: Int,
    val coverThumbPath: String?,
)

/** Albums of one type, e.g. all "place" albums, shown as a titled row. */
data class AlbumSection(
    val type: String,
    val albums: List<AlbumSummary>,
)

/**
 * One row in the Albums root. Either a real category derived from [AlbumSection]s
 * (Places / Cameras / Years) or a [SmartAlbum] that opens straight into a photo grid
 * (Videos / On this day). [itemCount] counts albums for the former, photos for the latter.
 */
data class AlbumCategory(
    val type: String,          // "place" | "camera" | "year" | "videos" | "onthisday"
    val itemCount: Int,
    val coverThumbPath: String?,
    val isSmart: Boolean = false,
)

/** One season (see [Seasons] codes) with its photo count and newest-photo cover, for the
 * Seasons drill-down. Only seasons that actually hold photos are surfaced. */
data class SeasonSummary(
    val season: Int,           // Seasons.WINTER..AUTUMN
    val photoCount: Int,
    val coverThumbPath: String?,
)

/** The dynamic, query-backed albums shown at the Albums root, distinct from the albums table. */
enum class SmartAlbumKind { VIDEOS, ON_THIS_DAY }

/**
 * A smart album: a live query over [PhotoEntity], not a row in the `albums` table. Built fresh
 * each time the catalog loads (On this day depends on the current date), and only surfaced when
 * it holds at least one photo.
 */
data class SmartAlbum(
    val kind: SmartAlbumKind,
    val photoCount: Int,
    val coverThumbPath: String?,
)

/** One month within a year, for the Years → year → months drill-down. */
data class MonthSummary(
    val year: Int,
    val month: Int,            // 1..12
    val photoCount: Int,
    val coverThumbPath: String?,
)
