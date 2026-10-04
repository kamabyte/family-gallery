package com.familygallery.tv.share

import com.familygallery.tv.data.PhotoEntity

/**
 * One file to hand to the system share sheet: where it lives on the SMB share, what the receiving
 * app should call it, and what it actually is.
 *
 * [relativePath] is share-relative in exactly the same sense as the catalog's `relative_path` /
 * `video_path` columns, so it can be passed straight to
 * [com.familygallery.tv.smb.SmbClient.copyToLocal].
 */
data class ShareTarget(
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
)

/**
 * Decides *which* file gets shared for a given catalog row.
 *
 * The rule is asymmetric on purpose:
 *
 *  * **Photos share the original.** A shared family photo is expected to be the real thing —
 *    the 1600px preview the viewer displays would be a silent downgrade of something the user
 *    believes they are sending in full.
 *  * **Videos share the indexer's proxy.** An original clip is routinely several GB; pulling it
 *    over SMB to a phone before the share sheet even opens is not a usable interaction, and the
 *    bounded H.264/AAC proxy is both small and playable by every receiving app. When a catalog
 *    predates proxies (`video_path` is null) there is nothing to fall back to but the original.
 *
 * Pure by design: this is the part worth testing, and it must answer the same on a device as it
 * does in a JVM unit test.
 */
object ShareTargets {

    fun of(photo: PhotoEntity): ShareTarget {
        val proxy = photo.videoPath
        return if (photo.isVideo && proxy != null) {
            ShareTarget(
                relativePath = proxy,
                // The proxy is always MP4 regardless of what the original was, so the name the
                // receiving app sees must not keep a ".mov"/".avi" extension that no longer
                // describes the bytes.
                displayName = withExtension(photo.filename, "mp4"),
                mimeType = "video/mp4",
            )
        } else {
            ShareTarget(
                relativePath = photo.relativePath,
                displayName = photo.filename,
                mimeType = mimeTypeFor(photo),
            )
        }
    }

    fun of(photos: List<PhotoEntity>): List<ShareTarget> = photos.map(::of)

    /**
     * The single `type` an `ACTION_SEND(_MULTIPLE)` intent must carry for a whole batch.
     *
     * Android has no way to give one type per item, and the type decides which apps the chooser
     * offers. Narrow as far as the batch allows — an exact type when everything agrees, then the
     * family wildcard ("image/" + star), and only [WILDCARD] for a genuinely mixed selection.
     */
    fun commonMimeType(targets: List<ShareTarget>): String {
        if (targets.isEmpty()) return WILDCARD
        val types = targets.mapTo(LinkedHashSet()) { it.mimeType }
        types.singleOrNull()?.let { return it }
        val families = types.mapTo(LinkedHashSet()) { it.substringBefore('/') }
        return families.singleOrNull()?.let { "$it/*" } ?: WILDCARD
    }

    /**
     * The catalog's `mime_type` is authored by the indexer and is the most accurate answer, but it
     * is nullable for rows written by older indexer runs; fall back to the extension, and finally
     * to the media family we already know from `media_type`.
     */
    private fun mimeTypeFor(photo: PhotoEntity): String {
        photo.mimeType?.takeIf { it.isNotBlank() && it.contains('/') }?.let { return it }
        mimeTypeForExtension(extensionOf(photo.filename))?.let { return it }
        return if (photo.isVideo) "video/*" else "image/*"
    }

    private fun extensionOf(filename: String): String =
        filename.substringAfterLast('.', "").lowercase()

    private fun withExtension(filename: String, extension: String): String {
        val base = filename.substringBeforeLast('.', filename)
        return "$base.$extension"
    }

    /**
     * Extension → MIME for the formats the indexer actually produces or copies. Deliberately a
     * plain table rather than `android.webkit.MimeTypeMap`: it stays pure (so it is unit-testable
     * off-device), it is deterministic across OEM builds, and it covers HEIC/DNG, which older
     * platform tables miss.
     */
    private fun mimeTypeForExtension(extension: String): String? = when (extension) {
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        "dng" -> "image/x-adobe-dng"
        "tif", "tiff" -> "image/tiff"
        "mp4", "m4v" -> "video/mp4"
        "mov", "qt" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mpg", "mpeg" -> "video/mpeg"
        "wmv" -> "video/x-ms-wmv"
        else -> null
    }

    private const val WILDCARD = "*/*"
}
