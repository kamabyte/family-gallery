package com.familygallery.tv.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Calendar

/**
 * Read-only access to the indexer-produced SQLite catalog via direct queries.
 *
 * Deliberately not Room: the DB is authored by an external tool, so plain SQL keeps us
 * decoupled from Room's strict schema/identity validation. All access is by simple
 * LIMIT/OFFSET windows, which is ideal for a static, read-only catalog.
 */
class CatalogDatabase private constructor(private val db: SQLiteDatabase) {

    fun photoCount(): Int = simpleCount("SELECT COUNT(*) FROM photos")

    fun albumPhotoCount(albumId: Long): Int = simpleCount(
        "SELECT COUNT(*) FROM photo_albums WHERE album_id = ?",
        arrayOf(albumId.toString()),
    )

    fun timeline(limit: Int, offset: Int): List<PhotoEntity> =
        queryPhotos(
            "SELECT * FROM photos ORDER BY capture_date DESC, id DESC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        )

    fun albumPhotos(albumId: Long, limit: Int, offset: Int): List<PhotoEntity> =
        queryPhotos(
            "SELECT p.* FROM photos p " +
                "JOIN photo_albums pa ON pa.photo_id = p.id " +
                "WHERE pa.album_id = ? " +
                // capture_date is duplicated into the immutable membership table so SQLite can
                // stream this order from its composite index instead of sorting a whole large
                // album again for every page.
                "ORDER BY pa.capture_date DESC, pa.photo_id DESC LIMIT ? OFFSET ?",
            arrayOf(albumId.toString(), limit.toString(), offset.toString()),
        )

    /** All non-empty albums with their cover thumbnail, ordered by type then rank. */
    fun albumsWithCovers(): List<AlbumSummary> {
        val out = ArrayList<AlbumSummary>()
        db.rawQuery(
            "SELECT a.id, a.name, a.type, a.photo_count, p.thumb_path AS cover_thumb " +
                "FROM albums a LEFT JOIN photos p ON p.id = a.cover_photo_id " +
                "WHERE a.photo_count > 0 " +
                "ORDER BY a.type ASC, a.sort_order ASC, a.name ASC",
            null,
        ).use { c ->
            val id = c.getColumnIndexOrThrow("id")
            val name = c.getColumnIndexOrThrow("name")
            val type = c.getColumnIndexOrThrow("type")
            val count = c.getColumnIndexOrThrow("photo_count")
            val cover = c.getColumnIndexOrThrow("cover_thumb")
            while (c.moveToNext()) {
                out.add(
                    AlbumSummary(
                        id = c.getLong(id),
                        name = c.getString(name),
                        type = c.getString(type),
                        photoCount = c.getInt(count),
                        coverThumbPath = if (c.isNull(cover)) null else c.getString(cover),
                    )
                )
            }
        }
        return out
    }

    fun photoById(id: Long): PhotoEntity? =
        queryPhotos("SELECT * FROM photos WHERE id = ? LIMIT 1", arrayOf(id.toString()))
            .firstOrNull()

    // --- Smart albums (dynamic, not backed by the albums table) --------------

    fun videoCount(): Int =
        simpleCount("SELECT COUNT(*) FROM photos WHERE media_type = 'video'")

    fun videos(limit: Int, offset: Int): List<PhotoEntity> =
        queryPhotos(
            "SELECT * FROM photos WHERE media_type = 'video' " +
                "ORDER BY capture_date DESC, id DESC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        )

    /** Thumbnail of the newest video, for the Videos root card. */
    fun videoCover(): String? = firstString(
        "SELECT thumb_path FROM photos WHERE media_type = 'video' " +
            "ORDER BY capture_date DESC, id DESC LIMIT 1",
    )

    /**
     * Photos captured on the same month-day as [monthDay] ("MM-dd") across every year — the
     * "On this day" album. `localtime` matches the month bucketing used by the timeline and the
     * Years drill-down, so a photo lands on the same calendar day the rest of the app shows it on.
     */
    fun onThisDayCount(monthDay: String): Int = simpleCount(
        "SELECT COUNT(*) FROM photos WHERE $MONTH_DAY_EXPR = ?",
        arrayOf(monthDay),
    )

    fun onThisDayPhotos(monthDay: String, limit: Int, offset: Int): List<PhotoEntity> =
        queryPhotos(
            "SELECT * FROM photos WHERE $MONTH_DAY_EXPR = ? " +
                "ORDER BY capture_date DESC, id DESC LIMIT ? OFFSET ?",
            arrayOf(monthDay, limit.toString(), offset.toString()),
        )

    /** Thumbnail of the newest "On this day" photo, for its root card. */
    fun onThisDayCover(monthDay: String): String? = firstString(
        "SELECT thumb_path FROM photos WHERE $MONTH_DAY_EXPR = ? " +
            "ORDER BY capture_date DESC, id DESC LIMIT 1",
        arrayOf(monthDay),
    )

    /**
     * One row per non-empty season across all years, ordered winter→autumn, each with its photo
     * count and newest-photo cover (the bare `thumb_path`/`MAX(capture_date)` min-max-row trick).
     */
    fun seasonSummaries(): List<SeasonSummary> {
        val out = ArrayList<SeasonSummary>()
        db.rawQuery(
            "SELECT $SEASON_CASE AS season, COUNT(*) AS c, thumb_path AS cover, " +
                "MAX(capture_date) AS newest FROM photos GROUP BY season ORDER BY season",
            null,
        ).use { c ->
            val si = c.getColumnIndexOrThrow("season")
            val ci = c.getColumnIndexOrThrow("c")
            val cov = c.getColumnIndexOrThrow("cover")
            while (c.moveToNext()) {
                out.add(
                    SeasonSummary(
                        season = c.getInt(si),
                        photoCount = c.getInt(ci),
                        coverThumbPath = if (c.isNull(cov)) null else c.getString(cov),
                    )
                )
            }
        }
        return out
    }

    fun seasonCount(season: Int): Int = simpleCount(
        "SELECT COUNT(*) FROM photos WHERE $MONTH_EXPR IN (${seasonMonthsCsv(season)})",
    )

    fun seasonPhotos(season: Int, limit: Int, offset: Int): List<PhotoEntity> =
        queryPhotos(
            "SELECT * FROM photos WHERE $MONTH_EXPR IN (${seasonMonthsCsv(season)}) " +
                "ORDER BY capture_date DESC, id DESC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        )

    /**
     * Months that have photos in [year], newest month first, each with its photo count and a
     * cover thumbnail. The bare `thumb_path` alongside `MAX(capture_date)` is SQLite's
     * documented "bare column takes the min/max row" behaviour, so the cover is the newest
     * photo of that month. `localtime` matches the timeline's default-timezone month bucketing.
     */
    fun monthsInYear(year: Int): List<MonthSummary> {
        val (start, end) = yearRange(year)
        val out = ArrayList<MonthSummary>()
        db.rawQuery(
            "SELECT CAST(strftime('%m', capture_date/1000, 'unixepoch', 'localtime') AS INTEGER) AS m, " +
                "COUNT(*) AS c, thumb_path AS cover, MAX(capture_date) AS newest " +
                "FROM photos WHERE capture_date >= ? AND capture_date < ? " +
                "GROUP BY m ORDER BY m DESC",
            arrayOf(start.toString(), end.toString()),
        ).use { c ->
            val mi = c.getColumnIndexOrThrow("m")
            val ci = c.getColumnIndexOrThrow("c")
            val cov = c.getColumnIndexOrThrow("cover")
            while (c.moveToNext()) {
                out.add(
                    MonthSummary(
                        year = year,
                        month = c.getInt(mi),
                        photoCount = c.getInt(ci),
                        coverThumbPath = if (c.isNull(cov)) null else c.getString(cov),
                    )
                )
            }
        }
        return out
    }

    fun monthPhotoCount(year: Int, month: Int): Int {
        val (start, end) = monthRange(year, month)
        return simpleCount(
            "SELECT COUNT(*) FROM photos WHERE capture_date >= ? AND capture_date < ?",
            arrayOf(start.toString(), end.toString()),
        )
    }

    fun monthPhotos(year: Int, month: Int, limit: Int, offset: Int): List<PhotoEntity> {
        val (start, end) = monthRange(year, month)
        return queryPhotos(
            "SELECT * FROM photos WHERE capture_date >= ? AND capture_date < ? " +
                "ORDER BY capture_date DESC, id DESC LIMIT ? OFFSET ?",
            arrayOf(start.toString(), end.toString(), limit.toString(), offset.toString()),
        )
    }

    fun close() = db.close()

    // --- internals -----------------------------------------------------------

    private fun queryPhotos(sql: String, args: Array<String>): List<PhotoEntity> {
        val out = ArrayList<PhotoEntity>()
        db.rawQuery(sql, args).use { c ->
            val idx = PhotoCols(c)
            while (c.moveToNext()) out.add(idx.read(c))
        }
        return out
    }

    private fun simpleCount(sql: String, args: Array<String>? = null): Int =
        db.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** First column of the first row as a nullable string, or null if the query is empty. */
    private fun firstString(sql: String, args: Array<String>? = null): String? =
        db.rawQuery(sql, args).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    /** Comma-separated 1-based months of [season] for an `IN (…)` clause. Integer literals from
     * our own [Seasons] table — no user input, so safe to inline. */
    private fun seasonMonthsCsv(season: Int): String =
        Seasons.monthsOf(season).joinToString(",")

    /** [start, end) epoch-ms bounds of a calendar year in the device's default timezone. */
    private fun yearRange(year: Int): Pair<Long, Long> {
        val start = Calendar.getInstance().apply { clear(); set(year, Calendar.JANUARY, 1) }
        val end = (start.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
        return start.timeInMillis to end.timeInMillis
    }

    /** [start, end) epoch-ms bounds of a calendar month (1-based) in the default timezone. */
    private fun monthRange(year: Int, month: Int): Pair<Long, Long> {
        val start = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        return start.timeInMillis to end.timeInMillis
    }

    /** Column indices resolved once per cursor, then reused per row. */
    private class PhotoCols(c: Cursor) {
        val id = c.getColumnIndexOrThrow("id")
        val relativePath = c.getColumnIndexOrThrow("relative_path")
        val filename = c.getColumnIndexOrThrow("filename")
        val mediaType = c.getColumnIndexOrThrow("media_type")
        val captureDate = c.getColumnIndexOrThrow("capture_date")
        val width = c.getColumnIndexOrThrow("width")
        val height = c.getColumnIndexOrThrow("height")
        val orientation = c.getColumnIndexOrThrow("orientation")
        val mimeType = c.getColumnIndexOrThrow("mime_type")
        val thumbPath = c.getColumnIndexOrThrow("thumb_path")
        val previewPath = c.getColumnIndexOrThrow("preview_path")
        val videoPath = c.getColumnIndexOrThrow("video_path")
        val durationMs = c.getColumnIndexOrThrow("duration_ms")
        val placeCity = c.getColumnIndexOrThrow("place_city")
        val placeCountry = c.getColumnIndexOrThrow("place_country")
        val cameraModel = c.getColumnIndexOrThrow("camera_model")

        fun read(c: Cursor) = PhotoEntity(
            id = c.getLong(id),
            relativePath = c.getString(relativePath),
            filename = c.getString(filename),
            mediaType = c.getString(mediaType),
            captureDate = c.getLong(captureDate),
            width = c.getInt(width),
            height = c.getInt(height),
            orientation = c.getInt(orientation),
            mimeType = if (c.isNull(mimeType)) null else c.getString(mimeType),
            thumbPath = c.getString(thumbPath),
            previewPath = c.getString(previewPath),
            videoPath = if (c.isNull(videoPath)) null else c.getString(videoPath),
            durationMs = if (c.isNull(durationMs)) null else c.getLong(durationMs),
            placeCity = if (c.isNull(placeCity)) null else c.getString(placeCity),
            placeCountry = if (c.isNull(placeCountry)) null else c.getString(placeCountry),
            cameraModel = if (c.isNull(cameraModel)) null else c.getString(cameraModel),
        )
    }

    companion object {
        /** SQLite "MM-dd" (localtime) for a photo's capture date; the "On this day" grouping key. */
        private const val MONTH_DAY_EXPR =
            "strftime('%m-%d', capture_date/1000, 'unixepoch', 'localtime')"

        /** SQLite 1-based capture month as an integer, in the device's default timezone. */
        private const val MONTH_EXPR =
            "CAST(strftime('%m', capture_date/1000, 'unixepoch', 'localtime') AS INTEGER)"

        /** `CASE <month> WHEN … THEN <seasonCode> … END`, built once from [Seasons] so the SQL
         * bucketing and the UI labels can never drift apart. */
        private val SEASON_CASE: String = buildString {
            append("CASE ").append(MONTH_EXPR).append(' ')
            for (s in Seasons.ALL) for (m in Seasons.monthsOf(s)) {
                append("WHEN ").append(m).append(" THEN ").append(s).append(' ')
            }
            append("END")
        }

        fun open(dbFile: File): CatalogDatabase {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                // Catalogs are immutable snapshots. Enforce that contract at the platform layer
                // so the app can never create legitimate uncheckpointed WAL data of its own.
                SQLiteDatabase.OPEN_READONLY,
            )
            return CatalogDatabase(db)
        }
    }
}
