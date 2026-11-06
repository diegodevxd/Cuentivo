package com.cuentivo.app.ui.product

import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun deliver_capture_delivers_and_deletes() = runBlocking {
        val deleted = mutableListOf<String>()
        val delivered = mutableListOf<String>()

        deliverCapture("capture.jpg", { deleted += it }) { delivered += it }

        assertEquals(listOf("capture.jpg"), delivered)
        assertEquals(listOf("capture.jpg"), deleted)
    }

    @Test
    fun a_failing_delivery_still_deletes_and_propagates_the_same_error() {
        val deleted = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                deliverCapture("capture.jpg", { deleted += it }) { error("no se pudo entregar") }
            }
        }

        assertEquals(listOf("capture.jpg"), deleted)
    }

    @Test
    fun a_cancelled_delivery_still_deletes_and_propagates_the_cancellation() {
        val deleted = mutableListOf<String>()

        assertThrows(CancellationException::class.java) {
            runBlocking {
                deliverCapture("capture.jpg", { deleted += it }) { throw CancellationException("se fue") }
            }
        }

        assertEquals(listOf("capture.jpg"), deleted)
    }
}
