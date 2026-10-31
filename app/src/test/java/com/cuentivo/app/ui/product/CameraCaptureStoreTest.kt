package com.cuentivo.app.ui.product

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCaptureStoreTest {
    @Test
    fun creates_unique_jpeg_uri_inside_camera_cache_directory() {
        val cache = Files.createTempDirectory("camera-cache").toFile()
        val store = CameraCaptureStore.forTesting(cache, "com.cuentivo.app.fileprovider")

        val first = store.createCaptureFileForTesting()
        val second = store.createCaptureFileForTesting()

        assertNotEquals(first, second)
        assertTrue(CameraCaptureStore.fileProviderPath(first.name).startsWith("product-camera/"))
        assertTrue(first.name.endsWith(".jpg"))
        assertTrue(first.isFile)
        assertTrue(second.isFile)
    }

    @Test
    fun cancellation_deletes_only_a_camera_file_and_rejects_outside_names_and_uris() {
        val cache = Files.createTempDirectory("camera-cache").toFile()
        val store = CameraCaptureStore.forTesting(cache, "com.cuentivo.app.fileprovider")
        val file = store.createCaptureFileForTesting()
        val outside = cache.resolveSibling("outside.jpg").apply { writeText("keep") }

        assertTrue(store.delete(file.name))
        assertFalse(file.exists())
        assertFalse(store.delete("../outside.jpg"))
        assertTrue(outside.exists())
    }
}
