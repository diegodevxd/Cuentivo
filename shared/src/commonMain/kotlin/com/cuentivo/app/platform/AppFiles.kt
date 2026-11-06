package com.cuentivo.app.platform

/** Dónde vive un archivo: caché (el sistema puede borrarlo) o persistente (sobrevive). */
enum class FileArea { CACHE, PERSISTENT }

/**
 * Los archivos propios de la app, detrás de un contrato común.
 *
 * `directory` y `name` son **un solo segmento cada uno**, nunca una ruta: quien llama no puede
 * nombrar un archivo fuera del árbol de la app. Un nombre puede venir de un documento remoto (una
 * foto de producto que llegó por sync), así que [SafeFileName] es la puerta y los métodos no tocan
 * el disco cuando reciben algo inválido.
 */
interface AppFiles {
    fun readBytes(area: FileArea, directory: String, name: String): ByteArray?
    fun writeBytes(area: FileArea, directory: String, name: String, bytes: ByteArray): Boolean
    fun delete(area: FileArea, directory: String, name: String): Boolean
    fun clear(area: FileArea, directory: String)
    fun list(area: FileArea, directory: String): List<String>

    /** Ruta absoluta del archivo, o `null` si no existe o el nombre no es válido. */
    fun absolutePath(area: FileArea, directory: String, name: String): String?
}

/**
 * Qué es un segmento aceptable.
 *
 * Rechaza vacío o en blanco, `/`, `\`, `.`, `..` y NUL. No es una lista de caracteres prohibidos por
 * gusto: cada uno de esos abre una forma de salirse del directorio o de nombrar algo que no es un
 * archivo normal. Puro y compartido, para que Android e iOS contesten lo mismo.
 */
object SafeFileName {
    fun isValid(segment: String): Boolean =
        segment.isNotBlank() &&
            segment != "." &&
            segment != ".." &&
            segment.none { it == '/' || it == '\\' || it == '\u0000' }
}
