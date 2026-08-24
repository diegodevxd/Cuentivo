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
    fun `main ai lab sources never reference MlKitOcrEngine`() {
        for (source in sourcesOf(mainDir)) {
            assertFalse(
                "main must not reference MlKitOcrEngine directly",
                source.contains("MlKitOcrEngine"),
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
    fun `debug lab is a fullscreen opaque modal instead of dashboard content`() {
        val screenSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()

        assertTrue(
            "the lab must use a Dialog so the dashboard cannot remain interactive or visible below it",
            screenSource.contains("Dialog("),
        )
        assertTrue(
            "the lab must fill the dialog's available viewport",
            screenSource.contains("usePlatformDefaultWidth = false"),
        )
        assertTrue(
            "the lab must paint an opaque surface background",
            screenSource.contains(".background(MaterialTheme.colorScheme.background)"),
        )
        assertTrue(
            "the lab must stay clear of status/navigation bars and the keyboard",
            screenSource.contains(".statusBarsPadding()") &&
                screenSource.contains(".navigationBarsPadding()") &&
                screenSource.contains(".imePadding()"),
        )
    }

    @Test
    fun `capture package has no Firebase network or Room imports`() {
        val captureDir = File("src/main/java/com/cuentivo/app/ai/capture")
        assertTrue("capture package must exist", captureDir.exists())
        for (source in sourcesOf(captureDir)) {
            val imports = source.lines().filter { it.startsWith("import ") }.joinToString("\n")
            assertFalse("capture must not import Firebase: $source", imports.contains("firebase"))
            assertFalse("capture must not import Firestore: $source", imports.contains("firestore"))
            assertFalse("capture must not import Room: $source", imports.contains("room"))
            assertFalse("capture must not import HTTP client: $source", imports.contains("okhttp") || imports.contains("retrofit") || imports.contains("axios"))
        }
    }

    @Test
    fun `chat UI renders a transcript and bottom composer but no confirmation action`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("transcript must use LazyColumn", source.contains("LazyColumn"))
        assertTrue("transcript must render user messages", source.contains("AiChatMessage.UserText"))
        assertTrue("capture sheet must offer camera", source.contains("ai_debug_chat_camera"))
        assertTrue("capture sheet must offer gallery", source.contains("ai_debug_chat_gallery"))
        assertFalse("no confirmation action label", source.contains("Confirmar operación"))
        assertFalse("no confirmation callback", source.contains("onConfirm"))
    }

    @Test
    fun `composer and capture are enabled for non-sending states`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue(
            "composer must be enabled whenever not Sending",
            source.contains("state != AiDebugLabState.Sending"),
        )
    }

    @Test
    fun `app passes AI header callback only while debug transport exists`() {
        val source = File("src/main/java/com/cuentivo/app/ui/CuentivoApp.kt").readText()
        assertTrue(source.contains("onOpenAi = debugAiLabTransport?.let"))
        assertFalse("floating entry must be removed", source.contains("AiDebugLabEntryButton"))
    }

    @Test
    fun `camera capture uses existing store and take picture contract without permissions or network`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("must use the existing CameraCaptureStore", source.contains("CameraCaptureStore"))
        assertTrue("must use TakePicture contract", source.contains("ActivityResultContracts.TakePicture"))
        assertTrue("must create a capture URI", source.contains("createCaptureUri"))
        assertTrue("must sweep stale captures", source.contains("deleteStale"))
        assertTrue("must delete the capture after the callback", source.contains("cameraStore.delete"))
        assertFalse("no CAMERA permission", source.contains("CAMERA"))
        assertFalse("no RECORD_AUDIO", source.contains("RECORD_AUDIO"))
        assertFalse("no Gemini client", source.contains("Gemini"))
        assertFalse("no network", source.contains("http"))
    }

    @Test
    fun `pending capture uri survives activity recreation via rememberSaveable`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("must use rememberSaveable for the pending URI", source.contains("rememberSaveable"))
        assertTrue("must define a URI saver", source.contains("Saver<Uri?, String>"))
        assertTrue("must bind the saver via stateSaver", source.contains("stateSaver"))
        assertTrue("must sweep stale captures on entry", source.contains("deleteStale(except = pendingCaptureUri)"))
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
