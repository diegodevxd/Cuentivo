package com.cuentivo.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest

/**
 * [AppFilesContractTest] sobre `NSFileManager`, con un directorio temporal nuevo por caso.
 *
 * Pendiente de CI: `iosTest` sólo corre en el runner de macOS.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAppFilesTest : AppFilesContractTest() {

    private val caseDirByFiles = mutableMapOf<AppFiles, String>()
    private val caseDirs = mutableListOf<String>()
    private val manager = NSFileManager.defaultManager

    override fun newFiles(): AppFiles {
        val caseDir = NSTemporaryDirectory().trimEnd('/') + "/cuentivo-appfiles-" + NSUUID().UUIDString
        caseDirs += caseDir
        val files = IosAppFiles(cacheRoot = "$caseDir/cache", filesRoot = "$caseDir/files")
        caseDirByFiles[files] = caseDir
        return files
    }

    override fun outsideEntries(files: AppFiles): List<String> {
        val caseDir = caseDirByFiles.getValue(files)
        return manager.contentsOfDirectoryAtPath(caseDir, error = null)
            ?.filterIsInstance<String>()
            ?.sorted()
            .orEmpty()
    }

    @AfterTest
    fun cleanUp() {
        caseDirs.forEach { manager.removeItemAtPath(it, error = null) }
        caseDirs.clear()
        caseDirByFiles.clear()
    }
}
