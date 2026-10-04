package com.familygallery.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Failure-path coverage for the coordinated catalog swap, using a fake [CatalogSyncIo] over
 * real temp files. These are exactly the recovery/safety behaviors that are impossible to
 * exercise on-device deterministically.
 */
class CatalogSyncCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun manifest(rev: Long, fp: String? = "fp$rev", generation: Long? = rev) =
        CatalogManifest(SUPPORTED_SCHEMA_VERSION, rev, "catalogs/index-$rev.db", fp, generation, 1)

    /** A CatalogProbe of a fully-valid v3 catalog carrying [fingerprint]. */
    private fun goodProbe(fingerprint: String?) = CatalogProbe(
        quickCheckOk = true,
        schemaVersion = SUPPORTED_SCHEMA_VERSION,
        fingerprint = fingerprint,
        tables = CatalogValidator.REQUIRED_TABLES,
        columns = CatalogValidator.REQUIRED_COLUMNS,
    )

    private val badProbe = CatalogProbe(false, null, null, emptySet(), emptyMap())

    /**
     * Configurable fake over real temp files. A file's content IS its fingerprint; the literal
     * "INVALID" (or a missing file) is structurally invalid. probe() reflects that, so the
     * coordinator's fingerprint comparison is genuinely exercised.
     */
    private inner class FakeIo(
        var manifest: CatalogManifest?,
        var storedRevision: Long,
        localContent: String? = null,
    ) : CatalogSyncIo {
        val dir = tmp.newFolder()
        val local = File(dir, "index.db").apply { if (localContent != null) writeText(localContent) }
        var downloadContent: String? = null
        var downloadThrows: Boolean = false
        var swapCommits: Boolean = true
        var pointerCommits: Boolean = true
        val warnings = mutableListOf<String>()
        var downloadCount = 0
        var swapCount = 0
        var probeCount = 0
        var storedGeneration: Long = -1
        var storedLocal: File? = null

        override fun probe(file: File): CatalogProbe {
            probeCount++
            if (!file.exists()) return badProbe
            val content = file.readText()
            return if (content == "INVALID") badProbe else goodProbe(content)
        }

        override fun readManifest(): CatalogManifest? = manifest
        override fun download(catalogRelPath: String, dest: File) {
            downloadCount++
            if (downloadThrows) throw IOException("network")
            dest.writeText(downloadContent ?: "INVALID")
        }
        override fun localCatalog(): File = local
        override fun newTempFile(): File = File(dir, "t-$swapCount-$downloadCount.tmp.db")
        override fun newLocalCatalog(manifest: CatalogManifest): File =
            File(dir, "published-${manifest.revision}-$swapCount-$downloadCount.db")
        override fun swap(tmp: File, dest: File): Boolean {
            swapCount++
            if (!swapCommits) return false
            return tmp.renameTo(dest) || run { tmp.copyTo(dest, overwrite = true); true }
        }
        override fun loadRevision(): Long = storedRevision
        override fun storeSynced(manifest: CatalogManifest, local: File): Boolean {
            if (!pointerCommits) return false
            storedRevision = manifest.revision
            storedGeneration = manifest.cacheGeneration
            storedLocal = local
            return true
        }
        override fun retireQuietly(file: File) { file.delete() }
        override fun deleteQuietly(file: File) { file.delete() }
        override fun warn(message: String) { warnings.add(message) }
    }

    @Test fun matchingRevisionAndFingerprintTrustedWithoutDownload() {
        val io = FakeIo(manifest(5, fp = "fp5"), storedRevision = 5, localContent = "fp5")
        val result = CatalogSyncCoordinator.ensure(io)
        assertEquals(0, io.downloadCount)
        assertEquals(io.local, result)
    }

    @Test fun matchingRevisionButWrongFingerprintReDownloads() {
        // Stored revision matches (5==5) but the local file's fingerprint doesn't match the
        // manifest — a stale/replaced/restored DB must NOT be trusted; fetch a fresh copy.
        val io = FakeIo(manifest(5, fp = "fp5-real"), storedRevision = 5, localContent = "fp5-stale")
            .apply { downloadContent = "fp5-real" }
        val result = CatalogSyncCoordinator.ensure(io)
        assertEquals(1, io.downloadCount)
        assertEquals("fp5-real", result.readText())
    }

    @Test fun structurallyValidCatalogFromAnotherRevisionReDownloads() {
        // A structurally valid but different catalog (different fingerprint) at the matching
        // stored revision is rejected in favor of the manifest's referenced catalog.
        val io = FakeIo(manifest(5, fp = "fpA"), storedRevision = 5, localContent = "fpB")
            .apply { downloadContent = "fpA" }
        assertEquals("fpA", CatalogSyncCoordinator.ensure(io).readText())
        assertEquals(1, io.downloadCount)
    }

    @Test fun corruptLocalWithMatchingRevisionReDownloads() {
        val io = FakeIo(manifest(5, fp = "fp5"), storedRevision = 5, localContent = "INVALID")
            .apply { downloadContent = "fp5" }
        val result = CatalogSyncCoordinator.ensure(io)
        assertEquals(1, io.downloadCount)
        assertEquals("fp5", result.readText())
        assertEquals(5L, io.storedRevision)
    }

    @Test fun legacyManifestWithoutFingerprintTrustsStructuralOnMatchingRevision() {
        // No manifest fingerprint (legacy) → structural validity + matching revision is enough;
        // no download.
        val io = FakeIo(manifest(5, fp = null), storedRevision = 5, localContent = "whatever")
        assertEquals(io.local, CatalogSyncCoordinator.ensure(io))
        assertEquals(0, io.downloadCount)
    }

    @Test fun offlineStructurallyValidLocalIsUsed() {
        val io = FakeIo(manifest = null, storedRevision = 5, localContent = "fp5")
        assertEquals(io.local, CatalogSyncCoordinator.ensure(io))
        assertEquals(0, io.downloadCount)
    }

    @Test fun offlineWithInvalidLocalThrows() {
        val io = FakeIo(manifest = null, storedRevision = 5, localContent = "INVALID")
        assertThrows(NoValidCatalogException::class.java) { CatalogSyncCoordinator.ensure(io) }
    }

    @Test fun freshInstallNoLocalNoManifestThrows() {
        val io = FakeIo(manifest = null, storedRevision = -1, localContent = null)
        assertThrows(NoValidCatalogException::class.java) { CatalogSyncCoordinator.ensure(io) }
    }

    @Test fun invalidRemoteKeepsValidLocalAndDoesNotAdvanceRevision() {
        val io = FakeIo(manifest(6, fp = "fp6"), storedRevision = 5, localContent = "fp5")
            .apply { downloadContent = "INVALID" }
        val result = CatalogSyncCoordinator.ensure(io)
        assertEquals("fp5", result.readText()) // kept the good local one
        assertEquals(5L, io.storedRevision)     // NOT advanced to 6
        assertTrue(io.warnings.any { it.contains("invalid") })
    }

    @Test fun downloadFailureKeepsValidLocal() {
        val io = FakeIo(manifest(6, fp = "fp6"), storedRevision = 5, localContent = "fp5")
            .apply { downloadThrows = true }
        assertEquals("fp5", CatalogSyncCoordinator.ensure(io).readText())
        assertEquals(5L, io.storedRevision)
    }

    @Test fun swapFailureKeepsValidLocalAndReportsRefreshFailure() {
        val io = FakeIo(manifest(6, fp = "fp6"), storedRevision = 5, localContent = "fp5").apply {
            downloadContent = "fp6"
            swapCommits = false
        }
        val result = CatalogSyncCoordinator.ensure(io)
        assertEquals("fp5", result.readText()) // previous DB retained intact
        assertEquals(5L, io.storedRevision)     // revision not advanced
        assertTrue(io.warnings.any { it.contains("publication") })
    }

    @Test fun successfulDownloadSwapsAndStoresRevisionAndGeneration() {
        val io = FakeIo(manifest(6, fp = "fp6", generation = 3), storedRevision = 5,
            localContent = "fp5").apply { downloadContent = "fp6" }
        val result = CatalogSyncCoordinator.ensure(io)
        assertEquals("fp6", result.readText())
        assertEquals(6L, io.storedRevision)
        assertEquals(3L, io.storedGeneration)
        assertEquals(result, io.storedLocal)
        assertFalse("old catalog is retired only after pointer commit", io.local.exists())
    }

    @Test fun pointerCommitFailureKeepsPreviousCatalogAndDeletesNewPublication() {
        val io = FakeIo(manifest(6, fp = "fp6"), storedRevision = 5, localContent = "fp5").apply {
            downloadContent = "fp6"
            pointerCommits = false
        }
        val result = CatalogSyncCoordinator.ensure(io)
        assertEquals(io.local, result)
        assertEquals("fp5", result.readText())
        assertEquals(5L, io.storedRevision)
        assertTrue(io.dir.listFiles().orEmpty().none { it.name.startsWith("published-") })
    }

    @Test fun localFileProbedOnceWhenTrusted() {
        // Avoid probing the same local SQLite file multiple times (structure + fingerprint use
        // one snapshot).
        val io = FakeIo(manifest(5, fp = "fp5"), storedRevision = 5, localContent = "fp5")
        CatalogSyncCoordinator.ensure(io)
        assertEquals(1, io.probeCount)
    }
}

class CatalogSwapperTest {

    @get:Rule val tmp = TemporaryFolder()

    // A rename that actually performs the move (POSIX-like), for the happy path.
    private val realRename: (File, File) -> Unit = { s, d ->
        if (!s.renameTo(d)) throw IOException("rename failed")
    }

    @Test fun successPublishesToFreshImmutableDestination() {
        val dir = tmp.newFolder()
        val dest = File(dir, "catalog-local-new.db")
        val src = File(dir, "src.tmp.db").apply { writeText("new") }

        val result = CatalogSwapper.swap(src, dest, rename = realRename)

        assertTrue(result.committed)
        assertEquals("new", dest.readText())
        assertFalse(src.exists())
    }

    @Test fun existingDestinationIsRejectedWithoutRename() {
        val dir = tmp.newFolder()
        val dest = File(dir, "catalog-local-existing.db").apply { writeText("existing") }
        val src = File(dir, "src.tmp.db").apply { writeText("new") }
        var renameCalled = false

        val result = CatalogSwapper.swap(
            src, dest,
            rename = { _, _ -> renameCalled = true },
        )

        assertFalse(result.committed)
        assertTrue(result.reason!!.contains("already exists"))
        assertFalse(renameCalled)
        assertEquals("existing", dest.readText())
        assertTrue(src.exists())
    }

    @Test fun renameExceptionLeavesSourceAndFreshDestinationUnpublished() {
        val dir = tmp.newFolder()
        val dest = File(dir, "catalog-local-new.db")
        val src = File(dir, "src.tmp.db").apply { writeText("new") }

        val result = CatalogSwapper.swap(
            src, dest,
            rename = { _, _ -> throw IOException("errno 18 cross-device") },
        )

        assertFalse(result.committed)
        assertTrue(result.reason!!.contains("rename"))
        assertFalse(dest.exists())
        assertTrue(src.exists())
    }

    @Test fun activeCatalogAndSidecarsAreUntouchedByPublicationFailure() {
        val dir = tmp.newFolder()
        val active = File(dir, "catalog-local-active.db").apply { writeText("good-old-catalog") }
        val wal = File(dir, "catalog-local-active.db-wal").apply { writeText("active-wal") }
        val shm = File(dir, "catalog-local-active.db-shm").apply { writeText("active-shm") }
        val dest = File(dir, "catalog-local-new.db")
        val src = File(dir, "src.tmp.db").apply { writeText("new") }

        val result = CatalogSwapper.swap(
            src, dest,
            rename = { _, _ -> throw IOException("did not commit") },
        )

        assertFalse(result.committed)
        assertEquals("good-old-catalog", active.readText())
        assertEquals("active-wal", wal.readText())
        assertEquals("active-shm", shm.readText())
    }
}
