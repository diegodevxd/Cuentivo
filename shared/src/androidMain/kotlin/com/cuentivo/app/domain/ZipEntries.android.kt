package com.cuentivo.app.domain

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * `ZipInputStream`, tal cual se leía antes en `XlsxReader`.
 *
 * Es la versión ya publicada: no se cambia el mecanismo en Android, solo se le pone el filtro
 * delante para no descomprimir lo que nadie va a mirar (un .xlsx trae imágenes, estilos y temas).
 *
 * `closeEntry` se conserva a propósito: es lo que sigue validando el CRC de las entradas que no se
 * guardan, así que un archivo corrupto se sigue rechazando igual que ayer.
 */
internal actual fun readZipEntries(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, ByteArray> {
    val files = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory && wanted(entry.name)) files[entry.name] = zip.readBytes()
            zip.closeEntry()
        }
    }
    return files
}
