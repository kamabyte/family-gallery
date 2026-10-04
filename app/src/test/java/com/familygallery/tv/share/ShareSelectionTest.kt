package com.familygallery.tv.share

import com.familygallery.tv.data.PhotoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSelectionTest {

    private fun photo(id: Long) = PhotoEntity(
        id = id,
        relativePath = "2019/IMG_$id.JPG",
        filename = "IMG_$id.JPG",
        mediaType = "photo",
        captureDate = id,
        width = 100,
        height = 100,
        orientation = 0,
        mimeType = "image/jpeg",
        thumbPath = "t/$id.webp",
        previewPath = "p/$id.webp",
        videoPath = null,
        durationMs = null,
        placeCity = null,
        placeCountry = null,
        cameraModel = null,
    )

    private fun selectionOf(vararg ids: Long): Map<Long, PhotoEntity> =
        ids.associateWith { photo(it) }

    private fun changed(result: ShareSelection.Toggle): Map<Long, PhotoEntity> =
        (result as ShareSelection.Toggle.Changed).selection

    @Test
    fun `toggle adds an unselected photo`() {
        val result = changed(ShareSelection.toggle(emptyMap(), photo(7)))
        assertEquals(setOf(7L), result.keys)
    }

    @Test
    fun `toggle removes an already selected photo`() {
        val result = changed(ShareSelection.toggle(selectionOf(1, 2), photo(1)))
        assertEquals(setOf(2L), result.keys)
    }

    @Test
    fun `pick order is preserved so the share sheet gets the files as chosen`() {
        var selection: Map<Long, PhotoEntity> = emptyMap()
        listOf(5L, 3L, 9L).forEach { selection = changed(ShareSelection.toggle(selection, photo(it))) }
        assertEquals(listOf(5L, 3L, 9L), selection.keys.toList())
    }

    @Test
    fun `adding past the cap is refused, leaving the selection untouched`() {
        val full = selectionOf(*(1L..ShareSelection.MAX_ITEMS.toLong()).toList().toLongArray())
        assertTrue(ShareSelection.toggle(full, photo(999)) is ShareSelection.Toggle.LimitReached)
    }

    @Test
    fun `deselecting still works once the cap is reached`() {
        val full = selectionOf(*(1L..ShareSelection.MAX_ITEMS.toLong()).toList().toLongArray())
        val result = changed(ShareSelection.toggle(full, photo(1)))
        assertEquals(ShareSelection.MAX_ITEMS - 1, result.size)
    }
}
