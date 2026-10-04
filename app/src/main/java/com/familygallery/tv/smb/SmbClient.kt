package com.familygallery.tv.smb

import com.familygallery.tv.GalleryConfig
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Thin SMB2/3 client over SMBJ. One shared [DiskShare] is reused across reads (opening a
 * fresh connection per thumbnail would be far too slow), and many thumbnail reads run
 * concurrently against it.
 *
 * Resilience rules:
 *  - A **non-transient** error (missing file, access denied) is surfaced as-is and does NOT
 *    tear down the shared connection — one 404 must not disrupt every other in-flight read.
 *  - A **transient** transport/session error triggers exactly one reconnect+retry, guarded by
 *    a generation counter so a burst of concurrent failures causes a single reconnect, not a
 *    storm.
 *  - A connection that fails to fully build (auth or share-connect) is torn down before the
 *    error propagates, so we never leak a half-open socket/session.
 *
 * All calls here are blocking — call them from a background dispatcher.
 */
class SmbClient {

    private val lock = Any()

    @Volatile private var client: SMBClient? = null
    @Volatile private var connection: Connection? = null
    @Volatile private var session: Session? = null
    @Volatile private var diskShare: DiskShare? = null

    /** Advances every time we rebuild the connection; used to collapse reconnect storms. */
    @Volatile private var generation: Int = 0

    /** Read a whole file into memory. Intended for small files (thumbnails, manifest). */
    fun readBytes(relativePath: String): ByteArray =
        read(relativePath) { it.readBytes() }

    /**
     * Open [relativePath] for reading and run [block] with its stream. Used by the Coil
     * fetcher to copy straight into an okio buffer, avoiding a ByteArray→Buffer double copy.
     */
    fun <T> read(relativePath: String, block: (InputStream) -> T): T = withShare { share ->
        openForRead(share, relativePath).use { file ->
            file.inputStream.use(block)
        }
    }

    /** Stream a (possibly large) file to a local destination — used for the catalog DB. */
    fun copyToLocal(relativePath: String, dest: File) {
        withShare { share ->
            openForRead(share, relativePath).use { file ->
                file.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER) }
                }
            }
        }
    }

    /** True if the file exists on the share. */
    fun exists(relativePath: String): Boolean = withShare { share ->
        share.fileExists(toSmbPath(relativePath))
    }

    /**
     * Open a seekable SMB handle for Media3. Unlike an InputStream, SMBJ's positional read maps
     * directly to SMB range requests, so seeking in a video never downloads all preceding bytes.
     */
    fun openRandomAccess(relativePath: String): SmbRandomAccessFile = withShare { share ->
        val file = openForRead(share, relativePath)
        try {
            SmbRandomAccessFile(
                file = file,
                length = file.fileInformation.standardInformation.endOfFile,
            )
        } catch (e: Exception) {
            runCatching { file.close() }
            throw e
        }
    }

    fun close() = synchronized(lock) { closeQuietly() }

    // --- internals -----------------------------------------------------------

    private fun openForRead(share: DiskShare, relativePath: String): SmbFile =
        share.openFile(
            toSmbPath(relativePath),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )

    /**
     * Run [block] against a live share. On a transient failure, reconnect once and retry; on
     * a non-transient failure, propagate without disturbing the shared connection.
     */
    private fun <T> withShare(block: (DiskShare) -> T): T {
        val generationAtCall = generation
        return try {
            block(ensureShare())
        } catch (first: Exception) {
            if (!SmbErrorClassifier.isTransient(first)) throw wrap(first)
            val share = reconnectIfStale(generationAtCall)
            try {
                block(share)
            } catch (second: Exception) {
                throw wrap(second)
            }
        }
    }

    private fun wrap(e: Exception): Exception =
        if (e is SmbException) e
        else SmbException("SMB read failed for host ${GalleryConfig.HOST}", e)

    /**
     * Reconnect only if nobody else already did since this caller last saw the connection.
     * The generation check means a burst of concurrent transient failures collapses into a
     * single rebuild — the rest simply reuse the freshly established share.
     */
    private fun reconnectIfStale(generationAtCall: Int): DiskShare = synchronized(lock) {
        if (generation == generationAtCall) {
            closeQuietly()
            generation++
        }
        return ensureShareLocked()
    }

    private fun ensureShare(): DiskShare {
        diskShare?.let { if (it.isConnected) return it }
        return synchronized(lock) { ensureShareLocked() }
    }

    /** Must hold [lock]. Builds the connection, tearing down partial state on failure. */
    private fun ensureShareLocked(): DiskShare {
        diskShare?.let { if (it.isConnected) return it }
        closeQuietly()

        var smb: SMBClient? = null
        var conn: Connection? = null
        var sess: Session? = null
        try {
            smb = SMBClient(smbConfig())
            conn = smb.connect(GalleryConfig.HOST)
            val auth = if (GalleryConfig.isAnonymous) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(
                    GalleryConfig.USERNAME,
                    GalleryConfig.PASSWORD.toCharArray(),
                    GalleryConfig.DOMAIN,
                )
            }
            sess = conn.authenticate(auth)
            val share = sess.connectShare(GalleryConfig.SHARE) as? DiskShare
                ?: throw SmbException("\"${GalleryConfig.SHARE}\" is not a disk share")

            client = smb
            connection = conn
            session = sess
            diskShare = share
            return share
        } catch (e: Exception) {
            // Do not leak a partially constructed connection/session/socket.
            runCatching { sess?.close() }
            runCatching { conn?.close() }
            runCatching { smb?.close() }
            throw if (e is SmbException) e
            else SmbException("Could not connect to \\\\${GalleryConfig.HOST}\\${GalleryConfig.SHARE}", e)
        }
    }

    private fun closeQuietly() {
        runCatching { diskShare?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        diskShare = null; session = null; connection = null; client = null
    }

    /** Resolve a gallery-relative path against [GalleryConfig.BASE_PATH], as an SMB path. */
    private fun toSmbPath(relativePath: String): String {
        val joined = if (GalleryConfig.BASE_PATH.isBlank()) {
            relativePath
        } else {
            "${GalleryConfig.BASE_PATH.trim('/')}/$relativePath"
        }
        return joined.trim('/').replace('/', '\\')
    }

    private companion object {
        const val DEFAULT_BUFFER = 1 shl 16 // 64 KiB

        fun smbConfig(): SmbConfig = SmbConfig.builder()
            .withTimeout(TRANSACT_TIMEOUT_SEC, TimeUnit.SECONDS)  // per-transaction ceiling
            .withSoTimeout(SO_TIMEOUT_SEC, TimeUnit.SECONDS)      // socket read/connect ceiling
            .build()

        const val TRANSACT_TIMEOUT_SEC = 30L
        const val SO_TIMEOUT_SEC = 60L
    }
}

/** One Media3-owned SMB file handle. Reads are positional and therefore efficiently seekable. */
class SmbRandomAccessFile internal constructor(
    private val file: SmbFile,
    val length: Long,
) : Closeable {
    fun read(buffer: ByteArray, position: Long, offset: Int, length: Int): Int =
        file.read(buffer, position, offset, length)

    override fun close() = file.close()
}

/**
 * Classifies SMB failures so callers reconnect only when it can actually help. Extracted so
 * the policy is unit-testable without a live server.
 */
object SmbErrorClassifier {
    /**
     * Transient = worth reconnecting for (socket/transport dropped, session expired). A
     * missing file / access denied is a permanent answer for that request and must not bring
     * the shared connection down. Unknown errors default to non-transient.
     */
    fun isTransient(error: Throwable): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            when (cur) {
                is SMBApiException -> return cur.status.isTransientStatus()
                is java.net.SocketException,
                is java.net.SocketTimeoutException -> return true
                is IOException -> return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun NtStatus.isTransientStatus(): Boolean = when (this) {
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        NtStatus.STATUS_NO_SUCH_FILE,
        NtStatus.STATUS_ACCESS_DENIED,
        NtStatus.STATUS_OBJECT_NAME_INVALID,
        NtStatus.STATUS_SHARING_VIOLATION -> false
        // Session/connection level failures — reconnecting can recover these.
        NtStatus.STATUS_USER_SESSION_DELETED,
        NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
        NtStatus.STATUS_CONNECTION_DISCONNECTED,
        NtStatus.STATUS_CONNECTION_RESET,
        NtStatus.STATUS_IO_TIMEOUT -> true
        else -> false
    }
}

class SmbException(message: String, cause: Throwable? = null) : Exception(message, cause)
