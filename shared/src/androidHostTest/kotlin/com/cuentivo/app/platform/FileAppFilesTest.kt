package com.cuentivo.app.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest

/**
 * [AppFilesContractTest] sobre `java.io.File`, con un directorio temporal nuevo por caso.
 *
 * La "carpeta del caso" contiene `cache/` y `files/`; ahí es donde aparecería cualquier archivo que
 * se hubiera escapado con `..`.
 */
class FileAppFilesTest : AppFilesContractTest() {

    private val caseDirByFiles = mutableMapOf<AppFiles, File>()
    private val caseDirs = mutableListOf<File>()

    override fun newFiles(): AppFiles {
        val caseDir = Files.createTempDirectory("cuentivo-appfiles").toFile()
        caseDirs += caseDir
        val files = FileAppFiles(
            cacheRoot = File(caseDir, "cache"),
            filesRoot = File(caseDir, "files"),
        )
        caseDirByFiles[files] = caseDir
        return files
    }

    override fun outsideEntries(files: AppFiles): List<String> {
        val caseDir = caseDirByFiles.getValue(files)
        return caseDir.listFiles()?.map { it.name }?.sorted().orEmpty()
    }

    @AfterTest
    fun cleanUp() {
        caseDirs.forEach { it.deleteRecursively() }
        caseDirs.clear()
        caseDirByFiles.clear()
    }
}
