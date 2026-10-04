package com.familygallery.tv.smb

import com.familygallery.tv.data.CatalogManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class SmbErrorClassifierTest {

    @Test fun socketTimeoutIsTransient() {
        assertTrue(SmbErrorClassifier.isTransient(SocketTimeoutException("read timed out")))
    }

    @Test fun ioExceptionIsTransient() {
        assertTrue(SmbErrorClassifier.isTransient(IOException("connection reset")))
    }

    @Test fun nestedTransientCauseIsDetected() {
        val wrapped = RuntimeException("wrapper", IOException("socket closed"))
        assertTrue(SmbErrorClassifier.isTransient(wrapped))
    }

    @Test fun plainRuntimeExceptionIsNotTransient() {
        // Unknown errors default to non-transient so we don't tear down a healthy connection.
        assertFalse(SmbErrorClassifier.isTransient(IllegalStateException("nope")))
    }
}

class SmbCacheKeyTest {

    @Test fun sameLibraryPathGenerationGivesSameKey() {
        val a = SmbCacheKey.key("h", "s", "b", "7", "thumbs/ab/x_t.webp")
        val b = SmbCacheKey.key("h", "s", "b", "7", "thumbs/ab/x_t.webp")
        assertEquals(a, b)
    }

    @Test fun changingDerivativeGenerationChangesKey() {
        // The cache version is the derivative GENERATION. It advances on --rebuild / dimension /
        // quality / encoder changes, so the same content-hash path then misses stale bytes.
        assertNotEquals(
            SmbCacheKey.key("h", "s", "b", "7", "thumbs/ab/x_t.webp"),
            SmbCacheKey.key("h", "s", "b", "8", "thumbs/ab/x_t.webp"),
        )
    }

    @Test fun ordinaryRevisionChangeDoesNotChangeKey() {
        // An ordinary catalog update bumps the revision but NOT the derivative generation
        // (see CatalogManifest.cacheGeneration), so the cache version — and thus the key —
        // is unchanged and unchanged thumbnails stay cache hits.
        val genForRev10 = CatalogManifest(3, 10, "c", "f", 4, 1).cacheGeneration.toString()
        val genForRev11 = CatalogManifest(3, 11, "c", "f", 4, 1).cacheGeneration.toString()
        assertEquals(
            SmbCacheKey.key("h", "s", "b", genForRev10, "p"),
            SmbCacheKey.key("h", "s", "b", genForRev11, "p"),
        )
    }

    @Test fun changingLibraryIdentityChangesKey() {
        assertNotEquals(
            SmbCacheKey.key("host1", "s", "b", "7", "p"),
            SmbCacheKey.key("host2", "s", "b", "7", "p"),
        )
        assertNotEquals(
            SmbCacheKey.key("h", "share1", "b", "7", "p"),
            SmbCacheKey.key("h", "share2", "b", "7", "p"),
        )
        assertNotEquals(
            SmbCacheKey.key("h", "s", "base1", "7", "p"),
            SmbCacheKey.key("h", "s", "base2", "7", "p"),
        )
    }

    @Test fun differentPathChangesKey() {
        assertNotEquals(
            SmbCacheKey.key("h", "s", "b", "7", "a.webp"),
            SmbCacheKey.key("h", "s", "b", "7", "b.webp"),
        )
    }

    @Test fun keyPreservesLibraryIdentityComponent() {
        // Library identity must remain part of the key (not dropped in favor of revision).
        val key = SmbCacheKey.key("h", "s", "b", "7", "p")
        assertTrue(key.contains(SmbCacheKey.libraryId("h", "s", "b")))
    }
}
