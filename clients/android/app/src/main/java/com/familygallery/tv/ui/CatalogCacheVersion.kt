package com.familygallery.tv.ui

import androidx.compose.runtime.compositionLocalOf
import com.familygallery.tv.smb.SmbImage

/**
 * The active catalog revision (as a string), provided at the top of the Ready UI so any
 * composable building an [SmbImage] can stamp it onto the request. This is what actually
 * revision-keys the Coil cache: when a re-index bumps the revision, every image request gets
 * a new key and stale bytes under an unchanged content-hash path are not served.
 *
 * Read it and pass it into [SmbImage] via [smbImage]; do not read a global mutable at fetch
 * time, so keys stay internally consistent across a catalog swap.
 */
val LocalCatalogCacheVersion = compositionLocalOf { "" }

/** Builds an [SmbImage] stamped with the current catalog cache version, or null for a null path. */
fun smbImage(relativePath: String?, cacheVersion: String): SmbImage? =
    relativePath?.let { SmbImage(it, cacheVersion) }
