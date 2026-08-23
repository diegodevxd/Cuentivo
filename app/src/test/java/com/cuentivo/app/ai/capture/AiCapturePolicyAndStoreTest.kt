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

class AiCapturePolicyTest {

    @Test
    fun `allows jpeg png webp`() {
        assertTrue(AiCapturePolicy.isAllowedMime("image/jpeg"))
        assertTrue(AiCapturePolicy.isAllowedMime("image/png"))
        assertTrue(AiCapturePolicy.isAllowedMime("image/webp"))
    }

    @Test
    fun `rejects gif bmp null empty and path-like mime`() {
        assertNull(AiCapturePolicy.fileNameFor("x", "image/gif"))
        assertFalse(AiCapturePolicy.isAllowedMime("image/bmp"))
        assertFalse(AiCapturePolicy.isAllowedMime(null))
        assertFalse(AiCapturePolicy.isAllowedMime(""))
        assertFalse(AiCapturePolicy.isAllowedMime("image/jpeg/../../etc"))
    }

    @Test
    fun `byte cap boundaries`() {
        assertFalse(AiCapturePolicy.isWithinByteCap(0L))
        assertTrue(AiCapturePolicy.isWithinByteCap(1L))
        assertTrue(AiCapturePolicy.isWithinByteCap(AiCapturePolicy.MAX_BYTES))
        assertFalse(AiCapturePolicy.isWithinByteCap(AiCapturePolicy.MAX_BYTES + 1))
    }

    @Test
    fun `dimension cap boundaries`() {
        assertTrue(AiCapturePolicy.isWithinDimensions(1, 1))
        assertTrue(AiCapturePolicy.isWithinDimensions(4096, 4096))
        assertFalse(AiCapturePolicy.isWithinDimensions(0, 100))
        assertFalse(AiCapturePolicy.isWithinDimensions(100, 0))
        assertFalse(AiCapturePolicy.isWithinDimensions(4097, 100))
        assertFalse(AiCapturePolicy.isWithinDimensions(100, 4097))
    }

    @Test
    fun `filename generation uses valid uuid and allowlisted extension`() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals("capture-$uuid.jpg", AiCapturePolicy.fileNameFor(uuid, "image/jpeg"))
        assertEquals("capture-$uuid.png", AiCapturePolicy.fileNameFor(uuid, "image/png"))
        assertEquals("capture-$uuid.webp", AiCapturePolicy.fileNameFor(uuid, "image/webp"))
    }

    @Test
    fun `filename generation rejects path traversal and invalid uuid`() {
        assertNull(AiCapturePolicy.fileNameFor("../../etc/passwd", "image/jpeg"))
        assertNull(AiCapturePolicy.fileNameFor("", "image/jpeg"))
        assertNull(AiCapturePolicy.fileNameFor("not-a-uuid", "image/png"))
    }
}

class AiCaptureStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): AiCaptureStore = AiCaptureStore(tmp.root.resolve("ai-capture"))

    @Test
    fun `create returns a file inside baseDir with correct extension`() {
        val s = store()
        val file = s.createCaptureFile("image/jpeg")
        assertNotNull(file)
        assertTrue(file!!.canonicalPath.startsWith(tmp.root.canonicalPath))
        assertTrue(file.name.endsWith(".jpg"))
        assertTrue(file.name.startsWith("capture-"))
    }

    @Test
    fun `create rejects disallowed mime`() {
        assertNull(store().createCaptureFile("image/gif"))
        assertNull(store().createCaptureFile("../evil"))
    }

    @Test
    fun `delete removes the file`() {
        val s = store()
        val file = s.createCaptureFile("image/png")!!
        file.writeText("x")
        assertTrue(file.exists())
        s.deleteCapture(file)
        assertFalse(file.exists())
    }

    @Test
    fun `cleanupAll empties the directory`() {
        val s = store()
        val f1 = s.createCaptureFile("image/jpeg")!!
        val f2 = s.createCaptureFile("image/png")!!
        f1.writeText("a"); f2.writeText("b")
        s.cleanupAll()
        assertFalse(f1.exists())
        assertFalse(f2.exists())
    }

    @Test
    fun `two calls produce unique names`() {
        val s = store()
        val f1 = s.createCaptureFile("image/jpeg")!!
        val f2 = s.createCaptureFile("image/jpeg")!!
        assertTrue(f1.name != f2.name)
    }
}
