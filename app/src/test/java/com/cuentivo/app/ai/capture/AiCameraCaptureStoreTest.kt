package com.cuentivo.app.ai.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiCameraCaptureStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): AiCameraCaptureStore =
        AiCameraCaptureStore.forTesting(tmp.root.resolve("ai-camera"), "test.fileprovider")

    @Test
    fun `capture files live in ai-camera not product-camera`() {
        val file = store().createCaptureFileForTesting()
        assertEquals("ai-camera", file.parentFile.name)
        assertTrue(file.name.startsWith("ai-capture-"))
        assertTrue(file.name.endsWith(".jpg"))
    }

    @Test
    fun `capture names are unique`() {
        val s = store()
        assertTrue(s.createCaptureFileForTesting().name != s.createCaptureFileForTesting().name)
    }

    @Test
    fun `delete removes the file`() {
        val s = store()
        val file = s.createCaptureFileForTesting()
        file.writeText("x")
        assertTrue(s.delete(file.name))
        assertFalse(file.exists())
    }

    @Test
    fun `delete rejects external names and traversal`() {
        val s = store()
        assertFalse(s.delete("../outside.jpg"))
        assertFalse(s.delete(""))
        assertFalse(s.delete("sub/dir.jpg"))
    }

    @Test
    fun `deleteStale removes all ai-camera files`() {
        val s = store()
        val f1 = s.createCaptureFileForTesting()
        val f2 = s.createCaptureFileForTesting()
        f1.writeText("a")
        f2.writeText("b")
        s.deleteStaleExceptName(null)
        assertFalse(f1.exists())
        assertFalse(f2.exists())
    }

    @Test
    fun `deleteStale keeps the excepted file and removes the rest`() {
        val s = store()
        val keep = s.createCaptureFileForTesting()
        val other = s.createCaptureFileForTesting()
        keep.writeText("k")
        other.writeText("o")
        s.deleteStaleExceptName(keep.name)
        assertTrue(keep.exists())
        assertFalse(other.exists())
    }

    @Test
    fun `fileForName rejects traversal and external paths`() {
        val s = store()
        assertNull(s.fileForName("../outside.jpg"))
        assertNull(s.fileForName(""))
        assertNull(s.fileForName("sub/dir.jpg"))
        val file = s.createCaptureFileForTesting()
        assertNotNull(s.fileForName(file.name))
    }
}