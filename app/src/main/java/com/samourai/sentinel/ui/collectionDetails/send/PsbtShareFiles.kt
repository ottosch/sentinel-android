package com.samourai.sentinel.ui.collectionDetails.send

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * #566: each share writes a new cache file. A fixed name plus a
 * long-lived read grant let a previous recipient read the next PSBT.
 * The grant on the intent is the temporary one the chooser needs.
 * [deleteAll] runs when the review is cleared.
 */
internal object PsbtShareFiles {

    private const val DIR = "psbt-share"

    fun create(cacheDir: File): File {
        val dir = File(cacheDir, DIR)
        dir.mkdirs()
        return File(dir, "${UUID.randomUUID()}.psbt")
    }

    fun deleteAll(cacheDir: File) {
        val dir = File(cacheDir, DIR)
        dir.listFiles()?.forEach { file -> file.delete() }
        dir.delete()
    }

    /**
     * The share chooser. Read access is the temporary grant on this
     * intent only: no persistable flag and no grantUriPermission.
     */
    fun chooser(uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("PSBT", uri)
            type = "application/octet-stream"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("PSBT", uri)
        }
    }
}
