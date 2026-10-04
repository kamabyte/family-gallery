package com.familygallery.tv.data

import java.io.File

/**
 * Pure, JVM-testable catalog synchronization. All Android/SMB/SQLite/prefs specifics are
 * behind [CatalogSyncIo] so the branchy safety logic (validate-before-trust, keep-known-valid
 * on any failure, atomic-only replacement) can be exercised with fakes and real temp files.
 *
 * [GallerySync] provides the Android implementation of [CatalogSyncIo].
 */

/** Thrown only when there is genuinely no valid catalog to serve (fresh install, offline). */
class NoValidCatalogException(message: String) : Exception(message)

/** Ports the coordinator needs; implemented by the Android sync layer. */
interface CatalogSyncIo {
    /** Parsed remote manifest, or null if it can't be read/parsed (offline, corrupt). */
    fun readManifest(): CatalogManifest?

    /** Download the catalog at [catalogRelPath] (relative to `.gallery/`) into [dest]. Throws on failure. */
    fun download(catalogRelPath: String, dest: File)

    /**
     * Probe [file] once into a structural + fingerprint snapshot. The coordinator runs all
     * validation ([CatalogValidator]) against this single probe, so a file is never opened
     * twice just to check structure and then fingerprint separately.
     */
    fun probe(file: File): CatalogProbe

    /** The currently active local catalog (legacy stable name or immutable selected filename). */
    fun localCatalog(): File

    /** A unique temp file in the same directory as the local catalog. */
    fun newTempFile(): File

    /** A fresh immutable destination that has never been opened by SQLite. */
    fun newLocalCatalog(manifest: CatalogManifest): File

    /** Atomically publish [tmp] at the fresh [dest]. The active catalog is never overwritten. */
    fun swap(tmp: File, dest: File): Boolean

    fun loadRevision(): Long

    /** Atomically persist the synced identity and active local filename after publication. */
    fun storeSynced(manifest: CatalogManifest, local: File): Boolean

    /** Best-effort cleanup of a catalog that is no longer active, including its sidecars. */
    fun retireQuietly(file: File)

    /** Best-effort delete (used for temp cleanup); failures are non-fatal. */
    fun deleteQuietly(file: File)

    fun warn(message: String)
}

object CatalogSyncCoordinator {

    /**
     * Returns a path to a known-valid local catalog, or throws [NoValidCatalogException] when
     * none can be produced.
     *
     * Guarantees:
     *  - A local catalog is validated before it is trusted (a corrupt local file with a
     *    matching revision triggers a fresh download instead of failing forever).
     *  - Even when the stored and remote revisions match, the local catalog's fingerprint must
     *    match the manifest's — otherwise a stale/replaced/restored DB with a matching stored
     *    revision would be served forever. A mismatch forces a fresh download.
     *  - When the remote manifest is unavailable, the local catalog is used if structurally
     *    valid (offline fallback — no fingerprint to compare against).
     *  - Any recoverable remote download/validation/swap failure falls back to a known-valid
     *    existing catalog rather than erroring.
     *  - The active pointer/revision/generation advance only after publication actually commits.
     *  - Publication uses a fresh immutable filename, so the active database and its WAL/SHM
     *    sidecars are never modified before the new catalog is durable and selected.
     */
    fun ensure(io: CatalogSyncIo): File {
        val local = io.localCatalog()
        // Probe the local file ONCE; reuse the snapshot for both structural and fingerprint checks.
        val localProbe = if (local.exists()) io.probe(local) else null
        val localStructurallyValid = localProbe != null &&
            CatalogValidator.validate(localProbe, expectedFingerprint = null) is CatalogValidation.Valid

        val manifest = io.readManifest()
            ?: return if (localStructurallyValid) local
            else throw NoValidCatalogException("no remote manifest and no valid local catalog")

        // Trust the local file without downloading ONLY if it's structurally valid, its stored
        // revision matches the manifest, AND its fingerprint matches the manifest's (proving it
        // is exactly the catalog the manifest describes, not a stale/restored file).
        val upToDate = localStructurallyValid &&
            manifest.revision == io.loadRevision() &&
            CatalogValidator.validate(localProbe!!, manifest.fingerprint) is CatalogValidation.Valid
        if (upToDate) return local

        // Otherwise fetch a fresh copy (revision/fingerprint differ, or local missing/corrupt).
        val tmp = io.newTempFile()
        var published: File? = null
        try {
            io.download(manifest.catalogPath, tmp)
            val validation = CatalogValidator.validate(io.probe(tmp), manifest.fingerprint)
            if (validation is CatalogValidation.Invalid) {
                io.warn("remote catalog rev ${manifest.revision} invalid: ${validation.reason}")
                return keepLocalOrThrow(localStructurallyValid, local, "remote catalog invalid: ${validation.reason}")
            }
            published = io.newLocalCatalog(manifest)
            if (!io.swap(tmp, published)) {
                io.warn("atomic catalog publication did not commit; retaining previous catalog")
                return keepLocalOrThrow(localStructurallyValid, local, "catalog swap failed")
            }
            if (!io.storeSynced(manifest, published)) {
                io.warn("could not persist active catalog pointer; retaining previous catalog")
                io.deleteQuietly(published)
                return keepLocalOrThrow(localStructurallyValid, local, "catalog pointer commit failed")
            }
            // The pointer is committed: only now may the previous catalog and its sidecars go.
            if (local != published) runCatching { io.retireQuietly(local) }
            return published
        } catch (e: NoValidCatalogException) {
            throw e
        } catch (e: Exception) {
            published?.let(io::deleteQuietly) // orphan from a failed pre-pointer publication
            io.warn("catalog refresh failed: ${e.message}")
            return keepLocalOrThrow(localStructurallyValid, local, "refresh failed: ${e.message}")
        } finally {
            io.deleteQuietly(tmp)
        }
    }

    private fun keepLocalOrThrow(localValid: Boolean, local: File, reason: String): File =
        if (localValid) local else throw NoValidCatalogException(reason)
}

/**
 * Result of an immutable catalog publication. [committed] means the atomic rename succeeded.
 * [reason] explains a non-commit (for logging).
 */
data class SwapResult(val committed: Boolean, val reason: String? = null)

/**
 * Publishes a downloaded catalog under a fresh immutable filename using an atomic
 * same-filesystem rename. The active destination is never replaced, so its SQLite WAL/SHM
 * sidecars remain paired with it until the active pointer is committed and cleanup runs.
 */
object CatalogSwapper {
    fun swap(
        tmp: File,
        dest: File,
        rename: (File, File) -> Unit,
    ): SwapResult {
        if (dest.exists()) {
            return SwapResult(false, "immutable destination already exists: ${dest.name}")
        }
        return try {
            rename(tmp, dest)
            SwapResult(true)
        } catch (e: Exception) {
            SwapResult(false, "atomic rename failed: ${e.message}")
        }
    }
}
