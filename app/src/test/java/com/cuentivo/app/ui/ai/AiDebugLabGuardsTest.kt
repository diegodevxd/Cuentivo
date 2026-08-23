package com.cuentivo.app.ui.ai

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard that the AI debug lab is debug-only and non-executable:
 * - main sources never reference Firebase, the transport implementation or runnable actions;
 * - the release transport exists, uses the same API and stays Firebase-free;
 * - the debug transport delegates to the debug-only gateway.
 */
class AiDebugLabGuardsTest {

    private val mainDir = File("src/main/java/com/cuentivo/app/ui/ai")
    private val debugDir = File("src/debug/java/com/cuentivo/app/ui/ai")
    private val releaseDir = File("src/release/java/com/cuentivo/app/ui/ai")

    private fun sourcesOf(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()

    @Test
    fun `main ai lab sources never reference Firebase or the debug gateway`() {
        for (source in sourcesOf(mainDir)) {
            val imports = source.lines().filter { it.startsWith("import ") }.joinToString("\n")
            assertFalse("main must not import Firebase: $source", imports.contains("firebase"))
            assertFalse(
                "main must not reference the debug gateway",
                source.contains("FirebaseCallableAiGateway"),
            )
        }
    }

    @Test
    fun `main ai lab sources never expose an executable financial action`() {
        for (source in sourcesOf(mainDir)) {
            val imports = source.lines().filter { it.startsWith("import ") }.joinToString("\n")
            assertFalse("no Room/DAO imports in main", imports.contains("data.local"))
            assertFalse("no Room imports in main", imports.contains(".room."))
            assertFalse(
                "no financial confirmation action in main",
                source.contains("onConfirmSale") ||
                    source.contains("onSaveSale") ||
                    source.contains("onConfirmPayment") ||
                    source.contains("onRecordPayment"),
            )
        }
    }

    @Test
    fun `closing the lab invalidates the in-flight result before closing`() {
        val screenSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        // The close action must run the machine cancel (generation bump) before invoking onClose.
        assertTrue(
            "close must cancel through the machine first",
            screenSource.contains("AiDebugLabMachine.onCancel(model)"),
        )
        assertTrue(
            "close must invoke onClose after cancelling",
            screenSource.contains("onClose()"),
        )
        assertTrue(
            "submit must go through the safe policy",
            screenSource.contains("AiDebugLabSubmitPolicy.run"),
        )
    }

    @Test
    fun `release transport provides the same API without Firebase`() {
        val release = File("src/release/java/com/cuentivo/app/ui/ai/AiDebugTransportRelease.kt")
        assertTrue("release transport must exist", release.exists())
        val source = release.readText()
        assertFalse("release transport must not reference Firebase", source.contains("Firebase"))
        assertTrue("release transport must implement the shared API", source.contains("AiDebugTransport"))
    }

    @Test
    fun `debug transport delegates to the debug only gateway`() {
        val debug = File("src/debug/java/com/cuentivo/app/ui/ai/AiDebugTransportDebug.kt")
        assertTrue("debug transport must exist", debug.exists())
        val source = debug.readText()
        assertTrue("debug transport must use the debug gateway", source.contains("FirebaseCallableAiGateway"))
        assertTrue("debug transport must implement the shared API", source.contains("AiDebugTransport"))
    }
}
