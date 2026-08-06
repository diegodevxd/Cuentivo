package com.cuentivo.app.ui.product

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Owns the short-lived JPEG files handed to the external camera app. */
class CameraCaptureStore private constructor(
    private val providerContext: Context?,
    cacheDirectory: File,
    private val authority: String,
    private val uriFactory: (Context?, String, File) -> Uri,
) {
    private val directory = File(cacheDirectory, DIRECTORY)

    constructor(context: Context) : this(
        providerContext = context.applicationContext,
        cacheDirectory = context.applicationContext.cacheDir,
        authority = "${context.applicationContext.packageName}.fileprovider",
        uriFactory = { providerContext, authority, file ->
            FileProvider.getUriForFile(requireNotNull(providerContext), authority, file)
        },
    )

    /** Creates an empty, unique JPEG destination and returns its private content URI. */
    fun createCaptureUri(): Uri {
        val file = createCaptureFile()
        return runCatching { uriFactory(providerContext, authority, file) }
            .getOrElse {
                file.delete()
                throw it
            }
    }

    /** Deletes a capture by its generated filename, never a path supplied by the caller. */
    fun delete(fileName: String): Boolean = safeFile(fileName)?.delete() == true

    /** Deletes only a URI issued by this store's FileProvider camera path. */
    fun delete(uri: Uri): Boolean = captureName(uri)?.let(::delete) == true

    /**
     * Clears captures left behind by earlier dialogs, keeping [except] — the one whose camera app
     * may still be writing to it right now.
     */
    fun deleteStale(except: Uri?) {
        val keep = except?.let(::captureName)
        directory.listFiles()?.forEach { file ->
            if (file.name != keep) file.delete()
        }
    }

    internal fun fileFor(uri: Uri): File = requireNotNull(safeFile(uri.lastPathSegment.orEmpty()))

    internal fun createCaptureFileForTesting(): File = createCaptureFile()

    /**
     * The file name inside a URI this store issued, or null for anything else.
     *
     * The first path segment is the `name` attribute of the `cache-path` in
     * `res/xml/product_camera_paths.xml`, not its `path`. The two are kept identical on purpose:
     * if they ever drift, every delete here silently returns false and captures pile up in cache.
     */
    private fun captureName(uri: Uri): String? {
        val segments = uri.pathSegments
        if (uri.scheme != "content" || uri.authority != authority || segments.size != 2) return null
        if (segments[0] != DIRECTORY) return null
        return segments[1]
    }

    private fun createCaptureFile(): File {
        directory.mkdirs()
        return File.createTempFile("capture-", ".jpg", directory)
    }

    private fun safeFile(fileName: String): File? {
        if (fileName.isBlank() || fileName != File(fileName).name) return null
        val root = directory.canonicalFile
        val candidate = File(root, fileName).canonicalFile
        return candidate.takeIf { it.parentFile == root }
    }

    companion object {
        private const val DIRECTORY = "product-camera"

        internal fun fileProviderPath(fileName: String): String = "$DIRECTORY/$fileName"

        internal fun forTesting(cacheDirectory: File, authority: String): CameraCaptureStore =
            CameraCaptureStore(null, cacheDirectory, authority) { _, _, file ->
                // The real constructor delegates this exact path to FileProvider. Unit tests use
                // filesystem assertions because framework Uri parsing is unavailable on the JVM —
                // the URI half is covered by ProductPhotoSourceTest, on a device.
                throw UnsupportedOperationException("URI factory is not used by filesystem tests")
            }
    }
}
