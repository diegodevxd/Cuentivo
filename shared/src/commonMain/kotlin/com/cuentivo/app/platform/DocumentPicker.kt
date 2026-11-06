package com.cuentivo.app.platform

import androidx.compose.runtime.Composable

/**
 * Un archivo elegido por la persona.
 *
 * En Android es una `Uri` que sigue viva mientras exista el permiso; en iOS es una **copia temporal**
 * que el selector deja en la carpeta temporal de la app. Por eso se lee una vez y se borra: nadie
 * debe quedarse con la ruta.
 */
interface PickedDocument {
    /** Los bytes del archivo, o `null` si no se pudo abrir. */
    suspend fun readBytes(): ByteArray?
}

/**
 * Un destino donde escribir un archivo.
 *
 * La asimetría con [PickedDocument] es del sistema, no del diseño: Android pregunta *dónde guardar*
 * antes de tener los bytes, así que el destino ya es una `Uri` y escribir es inmediato. iOS necesita
 * **el archivo hecho** para presentar el exportador (`UIDocumentPickerViewController(forExportingURLs:)`),
 * así que el destino escribe primero un temporal, abre el selector y espera a que la persona decida.
 */
interface DocumentSaveTarget {
    /** `false` si la persona canceló. Lanza si no se pudo escribir. */
    suspend fun write(bytes: ByteArray): Boolean
}

/** Abre el selector de archivos. */
fun interface DocumentOpener {
    fun open()
}

/** Pide un destino con [suggestedName] y lo entrega por `onTarget`. */
fun interface DocumentSaver {
    fun create(suggestedName: String)
}

/**
 * Recuerda un abridor de archivos que entrega el elegido a [onOpened].
 *
 * Cancelar **no** llama a `onOpened`: no hay archivo, y quien llama no tiene nada que decidir. Es la
 * misma razón por la que [DocumentSaveTarget.write] contesta `false` en iOS en vez de lanzar.
 */
@Composable
expect fun rememberDocumentOpener(onOpened: (PickedDocument) -> Unit): DocumentOpener

/**
 * Recuerda un botón de "guardar como", que entrega el destino a [onTarget] cuando la persona ya
 * eligió. En Android eso ocurre después de elegir carpeta; en iOS, de inmediato (ver
 * [DocumentSaveTarget]). Cancelar no llama a [onTarget].
 */
@Composable
expect fun rememberDocumentSaver(
    mimeType: String,
    onTarget: (DocumentSaveTarget) -> Unit,
): DocumentSaver
