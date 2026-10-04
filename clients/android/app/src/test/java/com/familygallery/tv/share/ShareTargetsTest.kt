package com.familygallery.tv.share

import com.familygallery.tv.data.PhotoEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The share-source policy: photos go out as originals, videos as the indexer's proxy. Both halves
 * are load-bearing — the first is a quality promise to the user, the second is what keeps a share
 * from pulling a multi-gigabyte clip over SMB.
 */
class ShareTargetsTest {

    private fun photo(
        filename: String = "IMG_0001.JPG",
        relativePath: String = "2019/IMG_0001.JPG",
        mediaType: String = "photo",
        mimeType: String? = "image/jpeg",
        videoPath: String? = null,
    ) = PhotoEntity(
        id = 1,
        relativePath = relativePath,
        filename = filename,
        mediaType = mediaType,
        captureDate = 0,
        width = 4000,
        height = 3000,
        orientation = 0,
        mimeType = mimeType,
        thumbPath = ".gallery/thumbs/1.webp",
        previewPath = ".gallery/previews/1.webp",
        videoPath = videoPath,
        durationMs = null,
        placeCity = null,
        placeCountry = null,
        cameraModel = null,
    )

    @Test
    fun `photo shares the original, not the preview`() {
        val target = ShareTargets.of(photo())
        assertEquals("2019/IMG_0001.JPG", target.relativePath)
        assertEquals("IMG_0001.JPG", target.displayName)
        assertEquals("image/jpeg", target.mimeType)
    }

    @Test
    fun `video shares the proxy and is renamed to match the bytes`() {
        val target = ShareTargets.of(
            photo(
                filename = "CLIP.MOV",
                relativePath = "2019/CLIP.MOV",
                mediaType = "video",
                mimeType = "video/quicktime",
                videoPath = ".gallery/video/7.mp4",
            ),
        )
        assertEquals(".gallery/video/7.mp4", target.relativePath)
        // The original's ".MOV" would misdescribe an MP4 proxy to the receiving app.
        assertEquals("CLIP.mp4", target.displayName)
        assertEquals("video/mp4", target.mimeType)
    }

    @Test
    fun `video without a proxy falls back to the original`() {
        val target = ShareTargets.of(
            photo(
                filename = "CLIP.MOV",
                relativePath = "2019/CLIP.MOV",
                mediaType = "video",
                mimeType = "video/quicktime",
                videoPath = null,
            ),
        )
        assertEquals("2019/CLIP.MOV", target.relativePath)
        assertEquals("CLIP.MOV", target.displayName)
        assertEquals("video/quicktime", target.mimeType)
    }

    @Test
    fun `missing catalog mime falls back to the extension`() {
        assertEquals("image/heic", ShareTargets.of(photo("IMG.HEIC", mimeType = null)).mimeType)
        assertEquals("image/x-adobe-dng", ShareTargets.of(photo("RAW.dng", mimeType = null)).mimeType)
        assertEquals("image/png", ShareTargets.of(photo("shot.PNG", mimeType = "")).mimeType)
    }

    @Test
    fun `unknown extension falls back to the media family`() {
        assertEquals("image/*", ShareTargets.of(photo("scan.xyz", mimeType = null)).mimeType)
        assertEquals(
            "video/*",
            ShareTargets.of(photo("clip.xyz", mediaType = "video", mimeType = null)).mimeType,
        )
    }

    @Test
    fun `common mime narrows as far as the batch allows`() {
        fun t(mime: String) = ShareTarget("p", "n", mime)

        assertEquals("image/jpeg", ShareTargets.commonMimeType(listOf(t("image/jpeg"), t("image/jpeg"))))
        assertEquals("image/*", ShareTargets.commonMimeType(listOf(t("image/jpeg"), t("image/png"))))
        assertEquals("video/*", ShareTargets.commonMimeType(listOf(t("video/mp4"), t("video/quicktime"))))
        assertEquals("*/*", ShareTargets.commonMimeType(listOf(t("image/jpeg"), t("video/mp4"))))
        assertEquals("*/*", ShareTargets.commonMimeType(emptyList()))
    }
}
