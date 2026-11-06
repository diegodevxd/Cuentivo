package com.cuentivo.app.platform

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * El selector de Android, sin cambiar lo que ya hacía la app.
 *
 * `OpenDocument` con el MIME comodín a propósito: los teléfonos y las nubes etiquetan `.csv` y
 * `.xlsx` de formas distintas, y un filtro estricto deja en gris justo el archivo que la persona vino
 * a elegir.
 */
@Composable
actual fun rememberDocumentOpener(onOpened: (PickedDocument) -> Unit): DocumentOpener {
    val resolver = LocalContext.current.contentResolver
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpened(AndroidPickedDocument(resolver, uri))
    }
    return remember(launcher) { DocumentOpener { launcher.launch(ARRAY_OF_ANYTHING) } }
}

@Composable
actual fun rememberDocumentSaver(
    mimeType: String,
    onTarget: (DocumentSaveTarget) -> Unit,
): DocumentSaver {
    val resolver = LocalContext.current.contentResolver
    val contract = remember(mimeType) { ActivityResultContracts.CreateDocument(mimeType) }
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        if (uri != null) onTarget(AndroidDocumentSaveTarget(resolver, uri))
    }
    return remember(launcher) { DocumentSaver { suggestedName -> launcher.launch(suggestedName) } }
}

private class AndroidPickedDocument(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : PickedDocument {

    /** `null` si el archivo no se puede abrir: una nube desconectada no puede tumbar la pantalla. */
    override suspend fun readBytes(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    }
}

private class AndroidDocumentSaveTarget(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : DocumentSaveTarget {

    override suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val stream = resolver.openOutputStream(uri)
            ?: error("El destino del respaldo no se pudo abrir")
        stream.use { it.write(bytes) }
        // Android eligió el destino antes de tener los bytes: si se llegó aquí, la persona no
        // canceló. Cancelar no llama a `onTarget` y este método nunca se invoca.
        true
    }
}

private val ARRAY_OF_ANYTHING = arrayOf("*/*")
