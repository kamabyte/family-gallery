package com.familygallery.tv.smb

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException

/**
 * Seekable Media3 source backed by SMBJ positional reads. The indexed MP4 has `faststart`, so
 * playback begins with a handful of small range requests instead of copying the whole video to
 * the TV first.
 */
@OptIn(markerClass = [UnstableApi::class])
class SmbDataSource(private val smb: SmbClient) : BaseDataSource(true) {
    private var openedUri: Uri? = null
    private var handle: SmbRandomAccessFile? = null
    private var readPosition = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        close()
        transferInitializing(dataSpec)

        val relativePath = dataSpec.uri.getQueryParameter(PATH_QUERY)
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Missing SMB media path")
        return try {
            val file = smb.openRandomAccess(relativePath)
            if (dataSpec.position > file.length) {
                file.close()
                throw EOFException("Position ${dataSpec.position} exceeds ${file.length}")
            }
            handle = file
            openedUri = dataSpec.uri
            readPosition = dataSpec.position
            val available = file.length - dataSpec.position
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                available
            } else {
                minOf(dataSpec.length, available)
            }
            opened = true
            transferStarted(dataSpec)
            bytesRemaining
        } catch (e: IOException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw IOException("Could not open SMB video", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        return try {
            val read = handle?.read(buffer, readPosition, offset, toRead)
                ?: return C.RESULT_END_OF_INPUT
            if (read <= 0) return C.RESULT_END_OF_INPUT
            readPosition += read
            bytesRemaining -= read
            bytesTransferred(read)
            read
        } catch (e: Exception) {
            throw IOException("SMB video read failed", e)
        }
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        runCatching { handle?.close() }
        handle = null
        openedUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val smb: SmbClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(smb)
    }

    companion object {
        private const val SCHEME = "family-gallery-smb"
        private const val PATH_QUERY = "path"

        fun uri(relativePath: String): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority("media")
            .appendQueryParameter(PATH_QUERY, relativePath)
            .build()
    }
}
