package com.cuentivo.app.platform

import java.io.File

/**
 * [AppFiles] sobre el sistema de archivos de Android.
 *
 * No se conecta a nada todavía: la app sigue usando `ProductImageStore` y `StatementImageShareStore`
 * como hasta hoy. Existe para que la implementación de iOS tenga un contrato que probar y para que
 * el día que una pieza común necesite archivos, las dos plataformas ya contesten igual.
 *
 * Cada método valida los segmentos con [SafeFileName] **antes** de construir cualquier `File`, así
 * que una ruta con `..` no llega a tocar el disco.
 */
class FileAppFiles(
    private val cacheRoot: File,
    private val filesRoot: File,
) : AppFiles {

    override fun readBytes(area: FileArea, directory: String, name: String): ByteArray? {
        val file = fileFor(area, directory, name) ?: return null
        return runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
    }

    override fun writeBytes(area: FileArea, directory: String, name: String, bytes: ByteArray): Boolean {
        val file = fileFor(area, directory, name) ?: return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    override fun delete(area: FileArea, directory: String, name: String): Boolean {
        val file = fileFor(area, directory, name) ?: return false
        return runCatching { file.isFile && file.delete() }.getOrDefault(false)
    }

    override fun clear(area: FileArea, directory: String) {
        val dir = directoryFor(area, directory) ?: return
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    override fun list(area: FileArea, directory: String): List<String> {
        val dir = directoryFor(area, directory) ?: return emptyList()
        return runCatching {
            dir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()
        }.getOrDefault(emptyList())
    }

    override fun absolutePath(area: FileArea, directory: String, name: String): String? {
        val file = fileFor(area, directory, name) ?: return null
        return runCatching { if (file.isFile) file.absolutePath else null }.getOrNull()
    }

    private fun directoryFor(area: FileArea, directory: String): File? {
        if (!SafeFileName.isValid(directory)) return null
        val root = when (area) {
            FileArea.CACHE -> cacheRoot
            FileArea.PERSISTENT -> filesRoot
        }
        return File(root, directory)
    }

    private fun fileFor(area: FileArea, directory: String, name: String): File? {
        if (!SafeFileName.isValid(name)) return null
        val dir = directoryFor(area, directory) ?: return null
        return File(dir, name)
    }
}
