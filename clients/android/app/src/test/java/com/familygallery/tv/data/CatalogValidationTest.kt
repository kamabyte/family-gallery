package com.familygallery.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogManifestTest {

    private val valid = """
        {
          "schema_version": 4,
          "revision": 7,
          "catalog": "catalogs/index-000007-abcdef012345.db",
          "content_fingerprint": "abcdef012345",
          "derivative_generation": 2,
          "photo_count": 1200
        }
    """.trimIndent()

    @Test fun parsesValidManifest() {
        val m = CatalogManifest.parse(valid)!!
        assertEquals(4, m.schemaVersion)
        assertEquals(7L, m.revision)
        assertEquals("catalogs/index-000007-abcdef012345.db", m.catalogPath)
        assertEquals("abcdef012345", m.fingerprint)
        assertEquals(2L, m.derivativeGeneration)
        assertEquals(2L, m.cacheGeneration) // uses the generation when present
        assertEquals(1200, m.photoCount)
    }

    @Test fun legacyManifestWithoutGenerationFallsBackToRevision() {
        val m = CatalogManifest.parse(
            """{"schema_version":4,"revision":9,"catalog":"catalogs/x.db"}""")!!
        assertNull(m.derivativeGeneration)
        assertEquals(9L, m.cacheGeneration) // conservative: revision-keyed until republished
    }

    @Test fun cacheGenerationIsIndependentOfRevision() {
        // Same derivative generation across two different revisions → same cache generation,
        // so an ordinary catalog update does not bust the image cache.
        val a = CatalogManifest(4, revision = 10, catalogPath = "c", fingerprint = "f",
            derivativeGeneration = 4, photoCount = 1)
        val b = a.copy(revision = 11)
        assertEquals(a.cacheGeneration, b.cacheGeneration)
    }

    @Test fun missingCatalogFieldFailsToParse() {
        assertNull(CatalogManifest.parse("""{"schema_version":4,"revision":7}"""))
    }

    @Test fun garbageFailsToParse() {
        assertNull(CatalogManifest.parse("not json"))
        assertNull(CatalogManifest.parse(""))
    }

    @Test fun absentFingerprintIsNull() {
        val m = CatalogManifest.parse(
            """{"schema_version":4,"revision":1,"catalog":"catalogs/x.db"}""")!!
        assertNull(m.fingerprint)
    }
}

class CatalogValidatorTest {

    /** A probe that mirrors a fully-valid v4 catalog (every required table + column present). */
    private fun goodProbe(
        quickCheckOk: Boolean = true,
        schema: Int? = SUPPORTED_SCHEMA_VERSION,
        fingerprint: String? = "fp",
        tables: Set<String> = CatalogValidator.REQUIRED_TABLES,
        columns: Map<String, Set<String>> = CatalogValidator.REQUIRED_COLUMNS,
    ) = CatalogProbe(quickCheckOk, schema, fingerprint, tables, columns)

    @Test fun validPasses() {
        assertTrue(CatalogValidator.validate(goodProbe(), "fp") is CatalogValidation.Valid)
    }

    @Test fun quickCheckFailureRejected() {
        assertTrue(CatalogValidator.validate(goodProbe(quickCheckOk = false), "fp")
            is CatalogValidation.Invalid)
    }

    @Test fun wrongSchemaRejected() {
        assertTrue(CatalogValidator.validate(goodProbe(schema = 2), "fp")
            is CatalogValidation.Invalid)
    }

    @Test fun fingerprintMismatchRejected() {
        assertTrue(CatalogValidator.validate(goodProbe(fingerprint = "other"), "fp")
            is CatalogValidation.Invalid)
    }

    @Test fun absentExpectedFingerprintSkipsThatCheck() {
        assertTrue(CatalogValidator.validate(goodProbe(fingerprint = null), null)
            is CatalogValidation.Valid)
    }

    /** Removing any required table must be rejected — one test per table. */
    @Test fun eachMissingTableRejected() {
        for (table in CatalogValidator.REQUIRED_TABLES) {
            val probe = goodProbe(
                tables = CatalogValidator.REQUIRED_TABLES - table,
                columns = CatalogValidator.REQUIRED_COLUMNS - table,
            )
            val r = CatalogValidator.validate(probe, "fp")
            assertTrue("missing table '$table' should be rejected", r is CatalogValidation.Invalid)
            assertTrue((r as CatalogValidation.Invalid).reason.contains(table))
        }
    }

    /**
     * Removing ANY single required column from ANY table must be rejected. This is the
     * regression guard for the finding: previously only a subset of photo columns (and no
     * album/photo_albums/meta columns) were checked, so an incomplete catalog could pass
     * validation and then crash at query time on getColumnIndexOrThrow.
     */
    @Test fun eachMissingColumnRejected() {
        for ((table, cols) in CatalogValidator.REQUIRED_COLUMNS) {
            for (col in cols) {
                val degraded = CatalogValidator.REQUIRED_COLUMNS.toMutableMap()
                degraded[table] = cols - col
                val r = CatalogValidator.validate(goodProbe(columns = degraded), "fp")
                assertTrue(
                    "$table.$col missing should be rejected",
                    r is CatalogValidation.Invalid,
                )
                assertTrue((r as CatalogValidation.Invalid).reason.contains(col))
            }
        }
    }

    @Test fun photoColumnListMatchesRuntimeReads() {
        // Guards against silently dropping a column the app reads. Mirrors PhotoCols.
        assertEquals(
            setOf(
                "id", "relative_path", "filename", "media_type", "capture_date",
                "width", "height", "orientation", "mime_type", "thumb_path", "preview_path",
                "video_path",
                "duration_ms", "place_city", "place_country", "camera_model",
            ),
            CatalogValidator.REQUIRED_COLUMNS.getValue("photos"),
        )
    }
}
