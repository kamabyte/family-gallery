package com.familygallery.tv.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.familygallery.tv.smb.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Turns catalog rows into content URIs the rest of Android can read.
 *
 * Nothing in this app's media is local: the files live on an SMB share that only this process can
 * reach (SMBJ, credentials from [com.familygallery.tv.GalleryConfig]). A share sheet hands a URI
 * to *another* app, so the bytes have to exist somewhere that app can open — hence a staging copy
 * in the app cache, exported through a [FileProvider] with a per-intent read grant.
 *
 * Staging is content-addressed by SMB path, so re-sharing a photo the user already sent does not
 * pull it over the network twice. The whole staging directory is pruned by age on every share
 * (see [RETENTION_MS]), which bounds both disk use and how stale a reused copy can be — a
 * re-indexed video proxy is picked up on the next day's share at the latest. Originals are
 * immutable in practice, so this is only a real consideration for proxies.
 */
class MediaShareStager(
    private val context: Context,
    private val smb: SmbClient,
) {

    /**
     * Copy every target off the share and return one content URI per target, in order.
     *
     * [onProgress] reports completed items (not bytes) so the caller can show "3 of 10" — the
     * only progress signal that means anything when item sizes differ by two orders of magnitude.
     * Runs on [Dispatchers.IO]; cancelling the calling scope stops between items and removes the
     * partial file.
     */
    suspend fun stage(
        targets: List<ShareTarget>,
        onProgress: (completed: Int) -> Unit = {},
    ): List<Uri> = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, SHARE_DIR)
        prune(root)
        root.mkdirs()

        targets.mapIndexed { index, target ->
            coroutineContext.ensureActive()
            val uri = uriFor(root, target)
            onProgress(index + 1)
            uri
        }
    }

    private fun uriFor(root: File, target: ShareTarget): Uri {
        // One directory per source path keeps the display name intact (the receiving app shows it
        // to the user) while making collisions between identically named files in different
        // folders impossible.
        val dir = File(root, digest(target.relativePath))
        dir.mkdirs()
        val dest = File(dir, sanitize(target.displayName))
        if (dest.length() == 0L) {
            try {
                smb.copyToLocal(target.relativePath, dest)
            } catch (e: Throwable) {
                dest.delete()
                throw e
            }
        } else {
            // Refresh the age so an item the user keeps sharing is not pruned mid-use.
            dest.setLastModified(System.currentTimeMillis())
        }
        return FileProvider.getUriForFile(context, authority(context), dest)
    }

    /**
     * Drop staged copies older than [RETENTION_MS]. Deliberately age-based rather than
     * "clear before every share": a receiving app may still be streaming a URI handed out a
     * moment ago, and deleting the file out from under it turns a successful share into a
     * corrupt attachment.
     */
    private fun prune(root: File) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        root.listFiles()?.forEach { entry ->
            val newest = entry.walkBottomUp().maxOfOrNull { it.lastModified() } ?: 0L
            if (newest < cutoff) entry.deleteRecursively()
        }
    }

    private fun digest(path: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(path.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /** Keep the name recognisable but incapable of escaping the staging directory. */
    private fun sanitize(displayName: String): String =
        displayName.substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("..", "_")
            .ifBlank { "photo" }

    private companion object {
        const val SHARE_DIR = "share"
        const val RETENTION_MS = 24L * 60 * 60 * 1000
    }
}

/** Must match the `android:authorities` of the provider declared in the manifest. */
private fun authority(context: Context): String = "${context.packageName}.fileprovider"

/**
 * Build the chooser for [uris].
 *
 * `EXTRA_STREAM` alone is not enough: the read grant only reaches the target app for URIs that are
 * also present in the intent's [ClipData], which is what actually carries permissions across the
 * process boundary. Both are therefore filled in, for the single and the multiple case alike.
 */
fun shareIntent(uris: List<Uri>, mimeType: String, chooserTitle: String): Intent {
    require(uris.isNotEmpty()) { "Nothing to share" }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = mimeType
    send.clipData = ClipData(
        ClipDescription(chooserTitle, arrayOf(mimeType)),
        ClipData.Item(uris.first()),
    ).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, chooserTitle)
}
