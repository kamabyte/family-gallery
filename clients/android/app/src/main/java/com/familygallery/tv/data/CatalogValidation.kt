package com.familygallery.tv.data

import org.json.JSONObject

/**
 * Pure, framework-free catalog-sync logic, extracted so the important safety decisions can
 * be unit-tested on the JVM without a device or a real SQLite file:
 *
 *  - [CatalogManifest.parse] turns the indexer's `manifest.json` into a typed value.
 *  - [CatalogValidator.validate] decides whether a downloaded catalog is safe to publish
 *    locally, from a plain [CatalogProbe] snapshot (which the Android layer fills in from
 *    the real SQLite file).
 *  - [SyncPlan.needsDownload] decides whether we even need to fetch a new catalog.
 *
 * The Android-SQLite–dependent probing lives in the sync layer; everything here is pure.
 */

/** Supported on-disk catalog schema. Bumped in lock-step with the indexer's SCHEMA_VERSION. */
const val SUPPORTED_SCHEMA_VERSION = 4

/** Typed view of `manifest.json`. Missing any required field yields a null parse. */
data class CatalogManifest(
    val schemaVersion: Int,
    val revision: Long,
    /** Catalog file path relative to the `.gallery/` dir, e.g. `catalogs/index-000001-ab.db`. */
    val catalogPath: String,
    /** Content fingerprint tying this manifest to its exact catalog file (may be absent). */
    val fingerprint: String?,
    /**
     * Indexer-owned derivative generation: advances only when derivative BYTES may change under
     * an existing path (rebuild / dimension / quality / encoder change), NOT on ordinary content
     * edits. The app keys its image cache on this so a plain catalog update doesn't invalidate
     * every thumbnail. Null on legacy manifests that predate the field.
     */
    val derivativeGeneration: Long?,
    val photoCount: Int,
) {
    /**
     * Value used to key the image cache. Prefers the derivative generation; on a legacy manifest
     * without it, falls back to the revision (conservative: per-revision invalidation, never
     * stale). This keeps ordinary catalog updates from busting the cache once the indexer
     * publishes the generation.
     */
    val cacheGeneration: Long get() = derivativeGeneration ?: revision

    companion object {
        fun parse(json: String): CatalogManifest? = runCatching {
            val o = JSONObject(json)
            val catalog = o.getString("catalog")
            require(catalog.isNotBlank())
            CatalogManifest(
                schemaVersion = o.getInt("schema_version"),
                revision = o.getLong("revision"),
                catalogPath = catalog,
                fingerprint = o.optString("content_fingerprint").ifBlank { null },
                derivativeGeneration = if (o.has("derivative_generation")) {
                    o.getLong("derivative_generation")
                } else {
                    null
                },
                photoCount = o.optInt("photo_count", 0),
            )
        }.getOrNull()
    }
}

/**
 * A snapshot of a catalog file's structure, produced by the Android SQLite layer.
 *
 * [columns] maps each present table name to its column-name set, so the validator can check
 * every column the runtime queries actually read — not just a subset.
 */
data class CatalogProbe(
    val quickCheckOk: Boolean,
    val schemaVersion: Int?,
    val fingerprint: String?,
    val tables: Set<String>,
    val columns: Map<String, Set<String>>,
)

sealed interface CatalogValidation {
    data object Valid : CatalogValidation
    data class Invalid(val reason: String) : CatalogValidation
}

object CatalogValidator {
    /**
     * Every table and column read by [CatalogDatabase]'s runtime queries. A catalog missing
     * any of these would pass a quick_check yet crash at query time on
     * `getColumnIndexOrThrow`, so all of them are validated up-front, before opening.
     */
    val REQUIRED_COLUMNS: Map<String, Set<String>> = mapOf(
        // photos: read by PhotoCols (timeline / albumPhotos / photoById) + album covers.
        "photos" to setOf(
            "id", "relative_path", "filename", "media_type", "capture_date",
            "width", "height", "orientation", "mime_type", "thumb_path", "preview_path",
            "video_path",
            "duration_ms", "place_city", "place_country", "camera_model",
        ),
        // albums: read by albumsWithCovers (+ cover_photo_id join, sort_order ordering).
        "albums" to setOf("id", "name", "type", "photo_count", "sort_order", "cover_photo_id"),
        // photo_albums: joined in albumPhotos / counted in albumPhotoCount.
        "photo_albums" to setOf("photo_id", "album_id", "capture_date"),
        // meta: schema_version / content_fingerprint lookups.
        "meta" to setOf("key", "value"),
    )

    val REQUIRED_TABLES: Set<String> = REQUIRED_COLUMNS.keys

    /**
     * A catalog is only trusted (published locally / opened) if it is structurally sound, on
     * the supported schema, has every table and column the app queries, and — when the
     * manifest carried one — matches the expected content fingerprint (proving we have exactly
     * the file the manifest referenced, not a half-swapped/older one).
     */
    fun validate(probe: CatalogProbe, expectedFingerprint: String?): CatalogValidation {
        if (!probe.quickCheckOk) return CatalogValidation.Invalid("quick_check failed")
        if (probe.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return CatalogValidation.Invalid(
                "unsupported schema ${probe.schemaVersion} (need $SUPPORTED_SCHEMA_VERSION)")
        }
        val missingTables = REQUIRED_TABLES - probe.tables
        if (missingTables.isNotEmpty()) {
            return CatalogValidation.Invalid("missing tables: ${missingTables.sorted()}")
        }
        for ((table, required) in REQUIRED_COLUMNS) {
            val present = probe.columns[table].orEmpty()
            val missing = required - present
            if (missing.isNotEmpty()) {
                return CatalogValidation.Invalid("$table missing columns: ${missing.sorted()}")
            }
        }
        if (expectedFingerprint != null && probe.fingerprint != expectedFingerprint) {
            return CatalogValidation.Invalid("fingerprint mismatch")
        }
        return CatalogValidation.Valid
    }
}
