package com.familygallery.tv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.familygallery.tv.GalleryConfig
import com.familygallery.tv.smb.SmbClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Probes a downloaded catalog file into a pure [CatalogProbe]. Android-SQLite backed. */
fun interface CatalogInspector {
    fun probe(file: File): CatalogProbe
}

/**
 * Keeps an immutable local catalog snapshot in sync with the versioned catalogs the indexer
 * publishes.
 *
 * The indexer never mutates a published catalog in place: it writes `catalogs/index-<rev>.db`
 * and atomically flips `manifest.json` to point at it. The actual sync decisions and the
 * atomic publication live in the pure [CatalogSyncCoordinator] / [CatalogSwapper]; this class only
 * supplies the Android/SMB/SQLite/prefs implementations via [CatalogSyncIo].
 *
 * Behavior (see the coordinator for the full contract):
 *  - A local catalog is validated before it's trusted; a corrupt local file with a matching
 *    revision re-downloads instead of failing on every retry.
 *  - When the manifest is unavailable, the local catalog is used only if valid.
 *  - Any recoverable remote failure keeps the known-valid existing catalog.
 *  - A download is atomically renamed to a fresh local filename; the active DB is never
 *    overwritten, so its WAL/SHM pair remains untouched on every failure path.
 *  - The active filename + revision/generation are committed synchronously before the previous
 *    local catalog is retired.
 */
class GallerySync(
    context: Context,
    private val smb: SmbClient,
    private val inspector: CatalogInspector = AndroidCatalogInspector(),
) {
    private val prefs by lazy {
        context.getSharedPreferences("gallery_sync", Context.MODE_PRIVATE)
    }

    private val galleryDir: File = File(context.filesDir, "gallery")
    private val localDb: File
        get() {
            val storedName = prefs.getString(KEY_LOCAL_DB_NAME, null)
                ?.takeIf { it.matches(LOCAL_CATALOG_NAME) }
            val stored = storedName?.let { File(galleryDir, it) }
            if (stored?.exists() == true) return stored
            val legacy = File(galleryDir, LEGACY_LOCAL_DB_NAME)
            if (legacy.exists()) return legacy
            // Recovery for a power loss where the pointer became durable but the directory entry
            // for the newly renamed file did not: preserve and use the newest prior immutable
            // catalog instead of deleting the last offline-capable fallback.
            return galleryDir.listFiles { f ->
                f.name.matches(LOCAL_CATALOG_NAME)
            }?.maxByOrNull { it.lastModified() } ?: (stored ?: legacy)
        }

    /** Ensures a valid local catalog exists and returns it. Blocking; call off the main thread. */
    fun ensureLocalDb(): File {
        galleryDir.mkdirs()
        cleanupTempFiles()
        val active = CatalogSyncCoordinator.ensure(io)
        cleanupOrphanCatalogs(active)
        return active
    }

    /** Revision of the currently-synced local catalog (-1 if never synced). */
    fun currentRevision(): Long = prefs.getLong(KEY_REVISION, -1L)

    /**
     * Cache-key generation for the currently-synced catalog: the derivative generation if we
     * stored one, else the revision (legacy), else "0". Stable across ordinary catalog updates
     * so image cache hits are preserved.
     */
    fun currentCacheVersion(): String {
        val gen = prefs.getLong(KEY_GENERATION, Long.MIN_VALUE)
        if (gen != Long.MIN_VALUE) return gen.toString()
        val rev = prefs.getLong(KEY_REVISION, Long.MIN_VALUE)
        return if (rev != Long.MIN_VALUE) rev.toString() else "0"
    }

    private val io = object : CatalogSyncIo {
        override fun readManifest(): CatalogManifest? = runCatching {
            CatalogManifest.parse(String(smb.readBytes(GalleryConfig.MANIFEST_PATH)))
        }.getOrNull()

        override fun download(catalogRelPath: String, dest: File) {
            smb.copyToLocal("${GalleryConfig.GALLERY_DIR}/$catalogRelPath", dest)
        }

        override fun probe(file: File): CatalogProbe = inspector.probe(file)

        override fun localCatalog(): File = localDb

        override fun newTempFile(): File = File(galleryDir, "catalog-${UUID.randomUUID()}$TMP_SUFFIX")

        override fun newLocalCatalog(manifest: CatalogManifest): File = File(
            galleryDir,
            "${LOCAL_CATALOG_PREFIX}${manifest.revision}-${UUID.randomUUID()}.db",
        )

        override fun swap(tmp: File, dest: File): Boolean {
            val result = CatalogSwapper.swap(
                tmp = tmp,
                dest = dest,
                rename = ::atomicRename,
            )
            if (!result.committed) Log.w(TAG, "catalog swap not committed: ${result.reason}")
            return result.committed
        }

        override fun loadRevision(): Long = prefs.getLong(KEY_REVISION, -1L)

        override fun storeSynced(manifest: CatalogManifest, local: File): Boolean =
            prefs.edit()
                .putLong(KEY_REVISION, manifest.revision)
                .putLong(KEY_GENERATION, manifest.cacheGeneration)
                .putString(KEY_LOCAL_DB_NAME, local.name)
                .commit()

        override fun retireQuietly(file: File) {
            deleteCatalogFiles(file)
        }

        override fun deleteQuietly(file: File) {
            if (file.exists() && !file.delete()) Log.w(TAG, "could not delete temp ${file.name}")
        }

        override fun warn(message: String) {
            Log.w(TAG, message)
        }
    }

    /**
     * Atomic same-filesystem publication via POSIX `rename(2)` ([android.system.Os.rename], API 21+),
     * which — unlike [File.renameTo] — has an explicit atomic-replacement contract. Best-effort
     * fsyncs the source bytes first.
     *
     * Durability note: `rename(2)` is atomic **with respect to concurrent observers** — a reader
     * sees either no destination or the whole new file, never a partial one. It is NOT, by itself,
     * guaranteed power-loss durable: fully durable replacement would also require fsync of the
     * containing directory, which Android/Java exposes no public API for. After a crash the file
     * bytes are fsync'd but the rename may be lost; that is safe here because the next launch
     * re-validates the local catalog (quick_check + schema + fingerprint) and re-syncs if needed.
     */
    private fun atomicRename(src: File, dest: File) {
        runCatching { FileOutputStream(src, true).use { it.fd.sync() } } // best-effort data durability
        try {
            Os.rename(src.absolutePath, dest.absolutePath)
        } catch (e: ErrnoException) {
            throw IOException("rename ${src.name} -> ${dest.name} failed (errno ${e.errno})", e)
        }
    }

    private fun cleanupTempFiles() {
        galleryDir.listFiles { f -> f.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }
    }

    /** Remove immutable local catalogs left orphaned by an interrupted pre-pointer commit. */
    private fun cleanupOrphanCatalogs(active: File) {
        galleryDir.listFiles { f ->
            f.name.startsWith(LOCAL_CATALOG_PREFIX) && f.name.endsWith(".db") && f != active
        }?.forEach(::deleteCatalogFiles)
    }

    private fun deleteCatalogFiles(db: File) {
        listOf(db, File("${db.absolutePath}-wal"), File("${db.absolutePath}-shm")).forEach {
            if (it.exists() && !it.delete()) Log.w(TAG, "could not delete retired ${it.name}")
        }
    }

    private companion object {
        const val TAG = "GallerySync"
        const val LEGACY_LOCAL_DB_NAME = "index.db"
        const val LOCAL_CATALOG_PREFIX = "catalog-local-"
        const val TMP_SUFFIX = ".tmp.db"
        const val KEY_REVISION = "catalog_revision"
        const val KEY_GENERATION = "derivative_generation"
        const val KEY_LOCAL_DB_NAME = "local_catalog_name"
        val LOCAL_CATALOG_NAME = Regex("^catalog-local-[0-9]+-[0-9a-fA-F-]+\\.db$")
    }
}

/** Real probe backed by Android's SQLite. Never runs on plain JVM unit tests. */
class AndroidCatalogInspector : CatalogInspector {
    override fun probe(file: File): CatalogProbe {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
            val tables = tableNames(db)
            CatalogProbe(
                quickCheckOk = quickCheck(db),
                schemaVersion = metaValue(db, "schema_version")?.toIntOrNull(),
                fingerprint = metaValue(db, "content_fingerprint"),
                tables = tables,
                // Only probe columns of tables the validator cares about, and only if present.
                columns = CatalogValidator.REQUIRED_TABLES
                    .filter { it in tables }
                    .associateWith { columnNames(db, it) },
            )
        } catch (e: Exception) {
            CatalogProbe(false, null, null, emptySet(), emptyMap())
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun quickCheck(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA quick_check", null).use { c ->
            c.moveToFirst() && c.getString(0) == "ok"
        }

    private fun metaValue(db: SQLiteDatabase, key: String): String? =
        runCatching {
            db.rawQuery("SELECT value FROM meta WHERE key = ? LIMIT 1", arrayOf(key)).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()

    private fun tableNames(db: SQLiteDatabase): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    private fun columnNames(db: SQLiteDatabase, table: String): Set<String> {
        val out = HashSet<String>()
        runCatching {
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) out.add(c.getString(nameIdx))
            }
        }
        return out
    }
}
