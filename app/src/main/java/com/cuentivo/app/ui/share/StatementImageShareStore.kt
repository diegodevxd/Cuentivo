package com.cuentivo.app.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.cuentivo.app.domain.StatementImageContent
import java.io.File

/** Owns the short-lived PNG handed to the Android Sharesheet for one customer statement. */
class StatementImageShareStore(context: Context) {

    private val applicationContext = context.applicationContext
    private val directory = File(applicationContext.cacheDir, DIRECTORY)
    private var pendingFile: File? = null

    /** Replaces an older pending image, writes this statement privately, and grants read-only access. */
    fun createShareIntent(subject: String, text: String, content: StatementImageContent): Intent {
        clear()
        directory.mkdirs()
        val file = File.createTempFile("statement-", ".png", directory)
        try {
            val bitmap = StatementImageRenderer.render(content)
            try {
                check(file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }) {
                    "No se pudo escribir la imagen del estado de cuenta"
                }
            } finally {
                bitmap.recycle()
            }
            val uri = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.fileprovider",
                file,
            )
            pendingFile = file
            return Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                clipData = ClipData.newRawUri(subject, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /** Called once the Sharesheet returns; no statement PNG remains in this app cache. */
    fun clear() {
        pendingFile?.delete()
        pendingFile = null
        directory.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val DIRECTORY = "statement-share"
    }
}
