package com.familygallery.tv

/**
 * SMB connection settings, baked in at build time from `local.properties` (not in git):
 *
 *     gallery.host=192.168.1.10
 *     gallery.share=Media
 *     gallery.basePath=Photos
 *     # gallery.username= / gallery.password= / gallery.domain= — only if the share needs auth
 *
 * Anonymous access is the default (empty user/password). Point [HOST] at your NAS/PC and
 * [SHARE] at the shared folder that the indexer wrote its `.gallery/` folder into.
 *
 * Later this can be promoted to an on-device settings screen without touching the rest
 * of the app — everything reads from [GalleryConfig].
 */
object GalleryConfig {

    /** IP address (or hostname) of the machine hosting the SMB share. */
    const val HOST: String = BuildConfig.GALLERY_HOST

    /** SMB share name (the exported folder), e.g. \\192.168.1.10\Media -> "Media". */
    const val SHARE: String = BuildConfig.GALLERY_SHARE

    /**
     * Path inside the share to the gallery root — the folder you point the indexer's
     * `--source` at. Leave blank if the gallery sits at the share root; if photos live in
     * "Media/Photos", this is "Photos".
     */
    const val BASE_PATH: String = BuildConfig.GALLERY_BASE_PATH

    /** Anonymous access: leave both blank. Fill in only if your share requires auth. */
    const val USERNAME: String = BuildConfig.GALLERY_USERNAME
    const val PASSWORD: String = BuildConfig.GALLERY_PASSWORD
    const val DOMAIN: String = BuildConfig.GALLERY_DOMAIN

    /** Folder (relative to [BASE_PATH]) the indexer writes to. */
    const val GALLERY_DIR: String = ".gallery"

    /** Paths within the share, derived from [GALLERY_DIR]. */
    val DB_PATH: String get() = "$GALLERY_DIR/index.db"
    val MANIFEST_PATH: String get() = "$GALLERY_DIR/manifest.json"

    val isAnonymous: Boolean get() = USERNAME.isBlank()
}
