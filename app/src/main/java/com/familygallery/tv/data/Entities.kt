package com.familygallery.tv.data

/**
 * Plain read models mirroring rows in the indexer's SQLite catalog (schema_version 4; see
 * [SUPPORTED_SCHEMA_VERSION]). The DB is authored by `indexer/indexer.py` and read directly
 * (see [CatalogDatabase]), so these are just data holders — no ORM annotations, no schema
 * coupling. `captureDate` is epoch-ms of the photo's capture instant (see the indexer's
 * timezone note).
 */
data class PhotoEntity(
    val id: Long,
    val relativePath: String,
    val filename: String,
    val mediaType: String,      // "photo" | "video"
    val captureDate: Long,      // epoch ms
    val width: Int,
    val height: Int,
    val orientation: Int,
    val mimeType: String?,
    val thumbPath: String,      // relative to share root
    val previewPath: String,    // relative to share root
    val videoPath: String?,     // TV-friendly H.264/AAC proxy; null for photos
    val durationMs: Long?,
    val placeCity: String?,
    val placeCountry: String?,
    val cameraModel: String?,
) {
    val isVideo: Boolean get() = mediaType == "video"
}
