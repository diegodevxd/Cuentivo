package com.cuentivo.app.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.memcpy

/**
 * [AppFiles] sobre el sistema de archivos de iOS, con `NSFileManager`.
 *
 * Los segmentos se validan con [SafeFileName] antes de construir cualquier ruta, igual que en
 * Android: un nombre que viene de un documento remoto no puede escaparse del árbol de la app.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosAppFiles(
    private val cacheRoot: String,
    private val filesRoot: String,
) : AppFiles {

    private val manager = NSFileManager.defaultManager

    override fun readBytes(area: FileArea, directory: String, name: String): ByteArray? {
        val path = filePath(area, directory, name) ?: return null
        val data = manager.contentsAtPath(path) ?: return null
        return data.toByteArray()
    }

    override fun writeBytes(area: FileArea, directory: String, name: String, bytes: ByteArray): Boolean {
        val path = filePath(area, directory, name) ?: return false
        // El directorio puede no existir todavía: la primera foto de una instalación nueva cae aquí.
        manager.createDirectoryAtPath(
            path = path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return bytes.toNSData().writeToFile(path, atomically = true)
    }

    override fun delete(area: FileArea, directory: String, name: String): Boolean {
        val path = filePath(area, directory, name) ?: return false
        if (!manager.fileExistsAtPath(path)) return false
        return manager.removeItemAtPath(path, error = null)
    }

    override fun clear(area: FileArea, directory: String) {
        val dir = directoryPath(area, directory) ?: return
        val names = manager.contentsOfDirectoryAtPath(dir, error = null)
            ?.filterIsInstance<String>()
            .orEmpty()
        for (name in names) {
            manager.removeItemAtPath("$dir/$name", error = null)
        }
    }

    override fun list(area: FileArea, directory: String): List<String> {
        val dir = directoryPath(area, directory) ?: return emptyList()
        return manager.contentsOfDirectoryAtPath(dir, error = null)
            ?.filterIsInstance<String>()
            ?.sorted()
            .orEmpty()
    }

    override fun absolutePath(area: FileArea, directory: String, name: String): String? {
        val path = filePath(area, directory, name) ?: return null
        return if (manager.fileExistsAtPath(path)) path else null
    }

    private fun directoryPath(area: FileArea, directory: String): String? {
        if (!SafeFileName.isValid(directory)) return null
        val root = when (area) {
            FileArea.CACHE -> cacheRoot
            FileArea.PERSISTENT -> filesRoot
        }
        if (root.isEmpty()) return null
        return "${root.trimEnd('/')}/$directory"
    }

    private fun filePath(area: FileArea, directory: String, name: String): String? {
        if (!SafeFileName.isValid(name)) return null
        val dir = directoryPath(area, directory) ?: return null
        return "$dir/$name"
    }
}

/**
 * Las dos raíces de la app: `NSCachesDirectory` (el sistema puede vaciarla) y
 * `NSApplicationSupportDirectory`, que se crea si falta porque no existe en una instalación nueva.
 */
fun defaultIosAppFiles(): IosAppFiles {
    val manager = NSFileManager.defaultManager
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: ""
    val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: ""
    if (support.isNotEmpty()) {
        manager.createDirectoryAtPath(
            path = support,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }
    return IosAppFiles(cacheRoot = caches, filesRoot = support)
}
