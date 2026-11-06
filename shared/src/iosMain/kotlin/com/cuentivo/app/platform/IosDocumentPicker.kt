package com.cuentivo.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

/**
 * El selector de documentos de iOS.
 *
 * El delegado de UIKit es una **referencia débil**: si no se guarda en un campo fuerte, el recolector
 * se lo lleva y el resultado nunca llega. [IosDocumentPickerHost] es ese campo, y vive en un
 * `remember` mientras la pantalla esté compuesta.
 *
 * El selector se cierra solo cuando la persona elige o cancela: llamar a `dismiss` aquí lo cerraría
 * dos veces.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberDocumentOpener(onOpened: (PickedDocument) -> Unit): DocumentOpener {
    val host = remember { IosDocumentPickerHost() }
    val current = rememberUpdatedState(onOpened)
    DisposableEffect(host) {
        onDispose { host.release() }
    }
    return remember(host) {
        DocumentOpener {
            val controller = topViewController()
            if (controller != null) {
                host.onOpen = { url -> current.value(IosPickedDocument(url)) }
                val picker = UIDocumentPickerViewController(
                    forOpeningContentTypes = listOf(UTTypeItem),
                    asCopy = true,
                )
                picker.delegate = host
                controller.presentViewController(picker, animated = true, completion = null)
            }
        }
    }
}

/**
 * Exporta con `UIDocumentPickerViewController(forExportingURLs:)`, que necesita el archivo ya hecho.
 *
 * Por eso `create` entrega el destino de inmediato: los bytes llegan después, cuando `write` los
 * tiene. Es la asimetría con Android, donde el destino se elige antes.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberDocumentSaver(
    mimeType: String,
    onTarget: (DocumentSaveTarget) -> Unit,
): DocumentSaver {
    val host = remember { IosDocumentPickerHost() }
    val current = rememberUpdatedState(onTarget)
    DisposableEffect(host) {
        onDispose { host.release() }
    }
    return remember(host) {
        DocumentSaver { suggestedName -> current.value(IosDocumentSaveTarget(host, suggestedName)) }
    }
}

/** Un archivo elegido: es la copia temporal que dejó el selector. */
@OptIn(ExperimentalForeignApi::class)
internal class IosPickedDocument(private val url: NSURL) : PickedDocument {

    override suspend fun readBytes(): ByteArray? = withContext(Dispatchers.Default) {
        val path = url.path ?: return@withContext null
        val data = NSFileManager.defaultManager.contentsAtPath(path) ?: return@withContext null
        // Leer es lo último que se hace con la copia: no se queda en la carpeta temporal.
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        data.toByteArray()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosDocumentSaveTarget(
    private val host: IosDocumentPickerHost,
    private val suggestedName: String,
) : DocumentSaveTarget {

    override suspend fun write(bytes: ByteArray): Boolean {
        val url = withContext(Dispatchers.Default) { writeDocumentTempFile(suggestedName, bytes) }
        return try {
            withContext(Dispatchers.Main) { host.presentExport(url) }
        } finally {
            // Sin `withContext`: en una corrutina cancelada lanzaría antes de borrar, y el respaldo se
            // quedaría en la carpeta temporal.
            deleteDocumentTempFile(url)
        }
    }
}

/**
 * El delegado fuerte de los dos selectores.
 *
 * `release` contesta `false` si había un exportador esperando: una pantalla que se va no puede dejar
 * una corrutina colgada para siempre.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosDocumentPickerHost : NSObject(), UIDocumentPickerDelegateProtocol {

    var onOpen: ((NSURL) -> Unit)? = null
    private var onExport: ((Boolean) -> Unit)? = null

    fun release() {
        onOpen = null
        val exported = onExport
        onExport = null
        exported?.invoke(false)
    }

    /** Presenta el exportador y suspende hasta que la persona decida. */
    suspend fun presentExport(url: NSURL): Boolean {
        val controller = topViewController() ?: error("No hay ventana para exportar el respaldo")
        return suspendCancellableCoroutine { continuation ->
            onExport = { saved -> if (continuation.isActive) continuation.resume(saved) }
            continuation.invokeOnCancellation { onExport = null }
            val picker = UIDocumentPickerViewController(
                forExportingURLs = listOf(url),
                asCopy = true,
            )
            picker.delegate = this
            controller.presentViewController(picker, animated = true, completion = null)
        }
    }

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val exported = onExport
        onExport = null
        if (exported != null) {
            exported(true)
            return
        }
        val open = onOpen
        onOpen = null
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url != null) open?.invoke(url)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onOpen = null
        val exported = onExport
        onExport = null
        exported?.invoke(false)
    }
}

/**
 * Escribe [bytes] en una subcarpeta única de la carpeta temporal y devuelve su URL.
 *
 * El nombre se valida con [SafeFileName] porque llega de la UI y termina siendo una ruta: un nombre
 * con `/` escribiría fuera de la carpeta.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writeDocumentTempFile(name: String, bytes: ByteArray): NSURL {
    require(SafeFileName.isValid(name)) { "Nombre de archivo inválido: $name" }
    val folder = NSTemporaryDirectory().trimEnd('/') + "/cuentivo-doc-" + NSUUID().UUIDString
    val manager = NSFileManager.defaultManager
    manager.createDirectoryAtPath(
        path = folder,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    val path = "$folder/$name"
    if (!bytes.toNSData().writeToFile(path, atomically = true)) {
        error("No se pudo escribir el archivo temporal")
    }
    return NSURL.fileURLWithPath(path)
}

/** Borra la subcarpeta entera: el temporal y su carpeta no dejan basura. */
@OptIn(ExperimentalForeignApi::class)
internal fun deleteDocumentTempFile(url: NSURL) {
    val path = url.path ?: return
    val folder = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (folder.isEmpty()) return
    NSFileManager.defaultManager.removeItemAtPath(folder, error = null)
}
