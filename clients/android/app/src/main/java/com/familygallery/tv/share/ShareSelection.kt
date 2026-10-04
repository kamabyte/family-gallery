package com.familygallery.tv.share

import com.familygallery.tv.data.PhotoEntity

/**
 * The multi-select model behind the phone grid's share mode.
 *
 * Selection is held as `id -> row` rather than a set of grid indices, because Paging evicts pages
 * the user has scrolled away from: an index-based selection would lose the ability to *resolve*
 * what was picked long before the user gets round to sharing it. Keeping the row itself means a
 * selection made 2000 photos ago is still shareable. Insertion order is preserved so the share
 * sheet receives the files in the order they were picked.
 *
 * Pure and framework-free so the cap and the toggle transitions are unit-testable.
 */
object ShareSelection {

    /**
     * Upper bound on one share. Every selected item is streamed off the NAS into the app cache
     * before the chooser opens, so an unbounded selection turns a tap into a multi-gigabyte
     * download; and no receiving app deals gracefully with hundreds of attachments anyway.
     */
    const val MAX_ITEMS = 50

    sealed interface Toggle {
        /** The new selection. Empty means selection mode should end. */
        data class Changed(val selection: Map<Long, PhotoEntity>) : Toggle

        /** Ignored: [MAX_ITEMS] already selected and this was an attempt to add one more. */
        data object LimitReached : Toggle
    }

    fun toggle(current: Map<Long, PhotoEntity>, photo: PhotoEntity): Toggle {
        if (photo.id in current) {
            return Toggle.Changed(current - photo.id)
        }
        if (current.size >= MAX_ITEMS) return Toggle.LimitReached
        return Toggle.Changed(current + (photo.id to photo))
    }
}
