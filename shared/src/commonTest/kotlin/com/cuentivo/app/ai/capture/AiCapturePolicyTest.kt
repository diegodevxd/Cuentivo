package com.cuentivo.app.ai.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
