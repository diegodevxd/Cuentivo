package com.cuentivo.app.ai.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
