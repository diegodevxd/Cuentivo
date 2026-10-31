package com.cuentivo.app.ai.capture

import java.io.File
import java.util.UUID

/**
 * Temporary file store for AI capture images, rooted at `cacheDir/ai-capture`.
 *
 * File names are always generated internally from a random UUID + allow-listed extension —
 * never derived from user input. Cleanup must be called on cancel/error/close and after OCR;
 * nothing persists beyond the lab session. No Firebase, no network, no Room, no logging.
 *
 * JVM-testable: takes a [File] base directory; the Android `context.cacheDir` binding happens
 * at the call site in the UI layer.
 */
class AiCaptureStore(private val baseDir: File) {

    init {
        baseDir.mkdirs()
    }

    /** Returns a new unique capture file for the given MIME, or null if the MIME is not allowed. */
    fun createCaptureFile(mime: String): File? {
        if (!AiCapturePolicy.isAllowedMime(mime)) return null
        val uuid = UUID.randomUUID().toString()
        val name = AiCapturePolicy.fileNameFor(uuid, mime) ?: return null
        val file = File(baseDir, name)
        // Path traversal defense: resolved path must stay inside baseDir.
        if (!file.canonicalPath.startsWith(baseDir.canonicalPath)) return null
        return file
    }

    /** Deletes a single capture file. Safe to call on a non-existent or null file. */
    fun deleteCapture(file: File?) {
        file?.delete()
    }

    /** Removes every file inside the capture directory. Called on cancel/close and after OCR. */
    fun cleanupAll() {
        baseDir.listFiles()?.forEach { it.delete() }
    }
}
