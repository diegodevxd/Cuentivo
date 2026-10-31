package com.cuentivo.app.ai.capture

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Isolated short-lived JPEG store for the AI debug camera capture, rooted at
 * `cacheDir/ai-camera` and exposed through the single app FileProvider under the `ai-camera`
 * cache-path. It never touches `product-camera` or any product photo.
 *
 * Equivalent safety to the product store: URIs are only ever issued for files this store created,
 * `safeFile`/canonical-path traversal defense applies, and `delete(uri)` rejects any URI or name
 * outside this store. No CAMERA permission, no network, no logging, no persistence.
 */
class AiCameraCaptureStore private constructor(
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

    /** Deletes only a URI issued by this store's FileProvider ai-camera path. */
    fun delete(uri: Uri): Boolean = captureName(uri)?.let(::delete) == true

    /**
     * Clears captures left behind by earlier dialogs, keeping [except] — the one whose camera app
     * may still be writing to it right now. Touches only this store's directory.
     */
    fun deleteStale(except: Uri?) {
        deleteStaleExceptName(except?.let(::captureName))
    }

    /**
     * Same cleanup, but sparing several captures at once. Two flows now write here at the same
     * time: a photo for the chat and a photographed ledger page. Sparing only one of them meant
     * starting one flow could delete the other one out from under the camera.
     */
    fun deleteStaleExcept(keep: Collection<Uri?>) {
        val keepNames = keep.filterNotNull().mapNotNull(::captureName).toSet()
        directory.listFiles()?.forEach { file ->
            if (file.name !in keepNames) file.delete()
        }
    }

    /** The file behind a URI this store issued; throws for anything else. */
    internal fun fileFor(uri: Uri): File =
        requireNotNull(captureName(uri)?.let(::safeFile))

    internal fun createCaptureFileForTesting(): File = createCaptureFile()

    internal fun deleteStaleExceptName(exceptName: String?) {
        directory.listFiles()?.forEach { file ->
            if (file.name != exceptName) file.delete()
        }
    }

    internal fun fileForName(fileName: String): File? = safeFile(fileName)

    /**
     * The file name inside a URI this store issued, or null for anything else. The first path
     * segment is the `name` attribute of the `cache-path` in `res/xml/product_camera_paths.xml`;
     * the two are kept identical on purpose.
     */
    private fun captureName(uri: Uri): String? {
        val segments = uri.pathSegments
        if (uri.scheme != "content" || uri.authority != authority || segments.size != 2) return null
        if (segments[0] != DIRECTORY) return null
        return segments[1]
    }

    private fun createCaptureFile(): File {
        directory.mkdirs()
        return File.createTempFile("ai-capture-", ".jpg", directory)
    }

    private fun safeFile(fileName: String): File? {
        if (fileName.isBlank() || fileName != File(fileName).name) return null
        val root = directory.canonicalFile
        val candidate = File(root, fileName).canonicalFile
        return candidate.takeIf { it.parentFile == root }
    }

    companion object {
        private const val DIRECTORY = "ai-camera"

        internal fun forTesting(cacheDirectory: File, authority: String): AiCameraCaptureStore =
            AiCameraCaptureStore(null, cacheDirectory, authority) { _, _, _ ->
                throw UnsupportedOperationException("URI factory is not used by filesystem tests")
            }
    }
}