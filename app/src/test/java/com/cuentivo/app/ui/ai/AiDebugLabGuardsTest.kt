package com.cuentivo.app.ui.ai

import java.io.File
import org.junit.Assert.assertEquals
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
    fun `main ai lab sources never expose direct DAO Room or sync imports`() {
        for (source in sourcesOf(mainDir)) {
            val imports = source.lines().filter { it.startsWith("import ") }.joinToString("\n")
            assertFalse("no financial DAO imports in main", imports.contains("Dao") || imports.contains("Entity") || imports.contains("CuentivoDatabase"))
            assertFalse("no financial repository in main", imports.contains("DashboardRepository") || imports.contains("SaleRepository"))
            assertFalse("no outbox or sync in main", imports.contains("sync") || imports.contains("SyncQueue"))
            assertFalse("no Room imports in main", imports.contains(".room."))
            assertFalse(
                "no direct unmediated save actions in main",
                source.contains("onSaveSale") || source.contains("onRecordPayment"),
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
    }

    @Test
    fun `screen wires daily chat repository and turn policy safely without persisting proposals`() {
        val screenSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue(
            "submit must go through AiDebugLabTurnPolicy.run",
            screenSource.contains("AiDebugLabTurnPolicy.run("),
        )
        assertFalse(
            "submit must not use raw transport submit",
            screenSource.contains("transport.submit("),
        )
        assertTrue(
            "screen must load history on entry with openToday",
            screenSource.contains("repository.openToday()"),
        )
        assertTrue(
            "screen must append trimmed user text to repository",
            screenSource.contains("repository.appendUserText("),
        )
        assertTrue(
            "screen must append assistant out of scope",
            screenSource.contains("repository.appendAssistant(AiDailyMessageKind.ASSISTANT_OUT_OF_SCOPE)"),
        )
        assertTrue(
            "screen must append assistant unavailable",
            screenSource.contains("repository.appendAssistant(AiDailyMessageKind.ASSISTANT_UNAVAILABLE)"),
        )
        assertFalse(
            "proposal must never be persisted",
            screenSource.contains("appendProposal") || screenSource.contains("appendAssistant(it.proposal"),
        )
        assertFalse(
            "clarification must never be persisted",
            screenSource.contains("appendClarification") || screenSource.contains("appendAssistant(it.clarification"),
        )
        assertTrue(
            "screen must support clearing today via clearToday",
            screenSource.contains("repository.clearToday()"),
        )
        assertTrue(
            "screen must handle repository errors safely with runCatching",
            screenSource.contains("runCatching"),
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
    fun `chat UI renders a transcript and bottom composer with neutral assistant cards`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("transcript must use LazyColumn", source.contains("LazyColumn"))
        assertTrue("transcript must render user messages", source.contains("AiChatMessage.UserText"))
        assertTrue("capture sheet must offer camera", source.contains("ai_debug_chat_camera"))
        assertTrue("capture sheet must offer gallery", source.contains("ai_debug_chat_gallery"))
        assertFalse("no hardcoded raw sale action string", source.contains("\"Confirmar operación\"") || source.contains("\"Confirmar venta\""))
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
    fun `app passes AI header callback only while debug transport and repository exist`() {
        val source = File("src/main/java/com/cuentivo/app/ui/CuentivoApp.kt").readText()
        assertTrue(
            "onOpenAi must check both debug transport and repository",
            source.contains("debugAiLabTransport != null && aiDailyChatRepository != null"),
        )
        assertFalse("floating entry must be removed", source.contains("AiDebugLabEntryButton"))
    }

    @Test
    fun `ai debug lab screen is keyed on daily chat repository to prevent state leaking between owners`() {
        val source = File("src/main/java/com/cuentivo/app/ui/CuentivoApp.kt").readText()
        assertTrue(
            "screen must be wrapped in key(aiDailyChatRepository)",
            source.contains("key(aiDailyChatRepository)") && source.contains("AiDebugLabScreen("),
        )
    }

    @Test
    fun `screen resets hydration to false before IO loading on repository change`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        val block = source.substring(
            source.indexOf("LaunchedEffect(repository)"),
            source.indexOf("repository.openToday()"),
        )
        assertTrue(
            "must set isHydrated = false before I/O in LaunchedEffect(repository)",
            block.contains("isHydrated = false"),
        )
    }

    @Test
    fun `camera capture uses existing store and take picture contract without permissions or network`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        // The ledger camera is the only camera in this screen now: the old OCR capture was the
        // second button people confused with it, and it is gone.
        val cameraBlock = source.substring(
            source.indexOf("val ledgerCameraLauncher"),
            source.indexOf("Dialog("),
        )
        assertTrue("must use the existing CameraCaptureStore", source.contains("CameraCaptureStore"))
        assertTrue("must use TakePicture contract", cameraBlock.contains("ActivityResultContracts.TakePicture"))
        assertTrue("must create a capture URI", source.contains("createCaptureUri"))
        assertTrue("must sweep stale captures", source.contains("deleteStale"))
        assertTrue("must delete the capture after the callback", cameraBlock.contains("cameraStore.delete"))
        assertFalse("no CAMERA permission in camera", cameraBlock.contains("CAMERA"))
        assertFalse("no Gemini client", source.contains("Gemini"))
        assertFalse("no network", source.contains("http"))
    }

    @Test
    fun `voice capture releases recognizer on screen exit and cleans up on dispose`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("must use DisposableEffect for recognizer cleanup", source.contains("DisposableEffect(effectiveVoiceRecognizer)"))
        assertTrue("must call destroy on dispose", source.contains("effectiveVoiceRecognizer.destroy()"))
        assertTrue("closeLab must destroy recognizer", source.contains("effectiveVoiceRecognizer.destroy()"))
        assertTrue("must cancel voice capture on ON_PAUSE or ON_STOP", source.contains("Lifecycle.Event.ON_PAUSE") && source.contains("Lifecycle.Event.ON_STOP"))
        assertTrue("must use VoiceCaptureMachine for state transitions", source.contains("VoiceCaptureMachine"))
        assertFalse("must not create direct SpeechRecognizer in screen", source.contains("SpeechRecognizer.createSpeechRecognizer"))
    }

    @Test
    fun `voice transcription fills text field without auto-submitting`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        val onFinalResultBlock = source.substring(
            source.indexOf("onFinalResult = {"),
            source.indexOf("onError = {"),
        )
        assertTrue(
            "transcription must feed onTextChanged",
            onFinalResultBlock.contains("AiDebugLabMachine.onTextChanged(model, transcribedText)"),
        )
        assertFalse(
            "transcription must NEVER call onSubmit automatically",
            onFinalResultBlock.contains("onSubmit"),
        )
    }

    @Test
    fun `pending capture uri survives activity recreation via rememberSaveable`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("must use rememberSaveable for the pending URI", source.contains("rememberSaveable"))
        assertTrue("must define a URI saver", source.contains("Saver<Uri?, String>"))
        assertTrue("must bind the saver via stateSaver", source.contains("stateSaver"))
        // The sweep must spare the page being photographed right now. It writes to the same
        // directory it cleans, so forgetting it deletes the photo out from under the camera.
        assertTrue(
            "must sweep stale captures on entry sparing the pending ledger capture",
            source.contains("deleteStaleExcept(listOf(pendingLedgerUri))"),
        )
    }

    @Test
    fun `screen uses isolated AI camera store not product camera`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("must use AiCameraCaptureStore", source.contains("AiCameraCaptureStore"))
        assertFalse(
            "must not import product CameraCaptureStore",
            source.contains("com.cuentivo.app.ui.product.CameraCaptureStore"),
        )
        assertFalse("must not reference product-camera", source.contains("product-camera"))
    }

    @Test
    fun `file provider exposes isolated ai-camera path alongside product-camera`() {
        val xml = File("src/main/res/xml/product_camera_paths.xml").readText()
        assertTrue("must expose ai-camera path", xml.contains("ai-camera"))
        assertTrue("must keep product-camera path", xml.contains("product-camera"))
    }

    @Test
    fun `fileFor derives only from captureName not raw lastPathSegment`() {
        val source = File("src/main/java/com/cuentivo/app/ai/capture/AiCameraCaptureStore.kt").readText()
        assertTrue(
            "fileFor must derive from captureName",
            source.contains("captureName(uri)?.let(::safeFile)"),
        )
        val fileForBlock = source.substring(
            source.indexOf("internal fun fileFor"),
            source.indexOf("internal fun createCaptureFileForTesting"),
        )
        assertFalse(
            "fileFor must not read uri.lastPathSegment directly",
            fileForBlock.contains("lastPathSegment"),
        )
    }

    @Test
    fun `release app check provider positively installs PlayIntegrityAppCheckProviderFactory`() {
        val releaseAppCheck = File("src/release/java/com/cuentivo/app/sync/AppCheckProvider.kt")
        assertTrue("release AppCheckProvider.kt must exist", releaseAppCheck.exists())
        val source = releaseAppCheck.readText()
        assertTrue(
            "release AppCheckProvider must positively install PlayIntegrityAppCheckProviderFactory",
            source.contains("PlayIntegrityAppCheckProviderFactory.getInstance()"),
        )
        assertFalse(
            "release AppCheckProvider must not use DebugAppCheckProviderFactory",
            source.contains("DebugAppCheckProviderFactory"),
        )

        // Debug App Check provider must never be referenced in release sources
        val releaseSources = File("src/release/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()
        for (src in releaseSources) {
            assertFalse(
                "release source must not reference DebugAppCheckProvider",
                src.contains("DebugAppCheckProvider") || src.contains("debugAppCheck"),
            )
        }
    }

    @Test
    fun `microphone permission is declared in manifest as direct uses-permission child for release voice dictation`() {
        val mainManifest = File("src/main/AndroidManifest.xml")
        assertTrue("main manifest must exist", mainManifest.exists())

        fun org.w3c.dom.Element.directChildren(name: String): List<org.w3c.dom.Element> {
            val list = mutableListOf<org.w3c.dom.Element>()
            val children = this.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE && node.nodeName == name) {
                    list.add(node as org.w3c.dom.Element)
                }
            }
            return list
        }

        fun checkManifest(file: File, description: String) {
            assertTrue("$description must exist at ${file.path}", file.exists())
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(file)
            val root = doc.documentElement
            assertEquals("root element must be manifest", "manifest", root.nodeName)

            val permissions = root.directChildren("uses-permission")
            val hasRecordAudio = permissions.any { it.getAttribute("android:name") == "android.permission.RECORD_AUDIO" }
            assertTrue(
                "RECORD_AUDIO must be declared as a direct uses-permission child of manifest in $description",
                hasRecordAudio,
            )
        }

        checkManifest(mainManifest, "main manifest")

        val releaseMergedManifest = File("build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml")
        checkManifest(releaseMergedManifest, "release merged manifest")
    }

    @Test
    fun `queries block declares RecognitionService intent action as direct children for Android 11+ package visibility`() {
        val mainManifest = File("src/main/AndroidManifest.xml")
        assertTrue("main manifest must exist", mainManifest.exists())

        fun org.w3c.dom.Element.directChildren(name: String): List<org.w3c.dom.Element> {
            val list = mutableListOf<org.w3c.dom.Element>()
            val children = this.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE && node.nodeName == name) {
                    list.add(node as org.w3c.dom.Element)
                }
            }
            return list
        }

        fun checkQueries(file: File, description: String) {
            assertTrue("$description must exist at ${file.path}", file.exists())
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(file)
            val root = doc.documentElement
            assertEquals("root element must be manifest", "manifest", root.nodeName)

            val queriesElements = root.directChildren("queries")
            assertTrue("manifest must contain at least one direct queries child in $description", queriesElements.isNotEmpty())

            val hasRecognitionService = queriesElements.any { queries ->
                queries.directChildren("intent").any { intent ->
                    intent.directChildren("action").any { action ->
                        action.getAttribute("android:name") == "android.speech.RecognitionService"
                    }
                }
            }
            assertTrue(
                "RecognitionService must be strictly declared under direct hierarchy: manifest > queries > intent > action in $description",
                hasRecognitionService,
            )
        }

        checkQueries(mainManifest, "main manifest")

        val releaseMergedManifest = File("build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml")
        checkQueries(releaseMergedManifest, "release merged manifest")
    }

    @Test
    fun `ai transport factories in debug and release return AiDebugFirebaseTransport`() {
        val debugFile = File("src/debug/java/com/cuentivo/app/ui/ai/AiDebugTransportDebug.kt")
        assertTrue("debug AiDebugTransportDebug.kt must exist", debugFile.exists())
        val debugSource = debugFile.readText()
        assertTrue(
            "debugAiTransport() in debug source set must return AiDebugFirebaseTransport",
            debugSource.contains("debugAiTransport(): AiDebugTransport = AiDebugFirebaseTransport()"),
        )

        val releaseFile = File("src/release/java/com/cuentivo/app/ui/ai/AiDebugTransportRelease.kt")
        assertTrue("release AiDebugTransportRelease.kt must exist", releaseFile.exists())
        val releaseSource = releaseFile.readText()
        assertTrue(
            "debugAiTransport() in release source set must return AiDebugFirebaseTransport",
            releaseSource.contains("debugAiTransport(): AiDebugTransport = AiDebugFirebaseTransport()"),
        )
        assertFalse(
            "debugAiTransport() in release must not return unavailable stub or null",
            releaseSource.contains("AiDebugUnavailableTransport") ||
                releaseSource.contains("debugAiTransport(): AiDebugTransport? = null") ||
                releaseSource.contains("debugAiTransport(): AiDebugTransport = null"),
        )
    }

    @Test
    fun `subscription verification factories in debug and release return FirebaseCallableSubscriptionVerificationGateway`() {
        val debugFile = File("src/debug/java/com/cuentivo/app/billing/SubscriptionVerificationTransportDebug.kt")
        assertTrue("debug SubscriptionVerificationTransportDebug.kt must exist", debugFile.exists())
        val debugSource = debugFile.readText()
        assertTrue(
            "subscriptionVerificationGateway() in debug must return FirebaseCallableSubscriptionVerificationGateway",
            debugSource.contains("FirebaseCallableSubscriptionVerificationGateway()"),
        )

        val releaseFile = File("src/release/java/com/cuentivo/app/billing/SubscriptionVerificationTransportRelease.kt")
        assertTrue("release SubscriptionVerificationTransportRelease.kt must exist", releaseFile.exists())
        val releaseSource = releaseFile.readText()
        assertTrue(
            "subscriptionVerificationGateway() in release must return FirebaseCallableSubscriptionVerificationGateway",
            releaseSource.contains("FirebaseCallableSubscriptionVerificationGateway()"),
        )
        assertFalse(
            "subscriptionVerificationGateway() in release must not return NoOp or stub gateway",
            releaseSource.contains("NoOpSubscriptionVerificationGateway"),
        )
    }

    @Test
    fun `main activity creates AiDailyChatRepository bound to currentUserId and dao`() {
        val source = File("src/main/java/com/example/organizationlabs/MainActivity.kt").readText()
        assertTrue("must pass aiDailyMessageDao", source.contains("database.aiDailyMessageDao()"))
        assertTrue("must bind ownerUserId to currentUserId", source.contains("ownerUserId = currentUserId"))
        assertTrue("must check for blank currentUserId", source.contains("currentUserId.isBlank()"))
        assertTrue("must pass aiDailyChatRepository to CuentivoApp", source.contains("aiDailyChatRepository = aiDailyChatRepository"))
    }

    @Test
    fun `main activity constructs AiPaymentPreviewResolver once using read only method references`() {
        val source = File("src/main/java/com/example/organizationlabs/MainActivity.kt").readText()
        assertTrue("must construct CustomerRepository", source.contains("CustomerRepository(database.customerDao())"))
        assertTrue("must construct AiPaymentPreviewResolver", source.contains("AiPaymentPreviewResolver("))
        assertTrue("must use customerRepository search reference", source.contains("customerRepository::search"))
        assertTrue("must use customerBalanceCents reference", source.contains("database.financialDao()::customerBalanceCents"))
        assertTrue("must pass paymentPreviewResolver to CuentivoApp", source.contains("paymentPreviewResolver ="))
    }

    @Test
    fun `main activity constructs AiPaymentExecutionService using local repository and sync queue and passes confirmPayment to CuentivoApp`() {
        val source = File("src/main/java/com/example/organizationlabs/MainActivity.kt").readText()
        assertTrue("must construct PaymentRepository", source.contains("PaymentRepository(database.financialDao())"))
        assertTrue("must construct AiPaymentExecutionService", source.contains("AiPaymentExecutionService("))
        assertTrue("must wire registerAgainstExpectedBalance", source.contains("paymentRepository::registerAgainstExpectedBalance"))
        assertTrue("must wire storedBundle", source.contains("paymentRepository::storedBundle"))
        assertTrue("must enqueue PAYMENT", source.contains("\"PAYMENT\""))
        assertTrue("must enqueue PAYMENT_ALLOCATION", source.contains("\"PAYMENT_ALLOCATION\""))
        assertTrue("must enqueue MOVEMENT", source.contains("\"MOVEMENT\""))
        assertTrue("must pass confirmPayment to CuentivoApp", source.contains("confirmPayment ="))
        assertTrue("must validate active business ID from DashboardUiState.Content", source.contains("(state as? DashboardUiState.Content)?.businessId"))
        assertTrue("must reject null or mismatched businessId", source.contains("activeBusinessId == null || activeBusinessId != request.businessId"))
        assertTrue("must return Rejected on mismatch", source.contains("AiPaymentExecutionResult.Rejected"))
    }

    @Test
    fun `main activity constructs AiSalePreviewResolver once using read only method references`() {
        val source = File("src/main/java/com/example/organizationlabs/MainActivity.kt").readText()
        assertTrue("must construct ProductRepository", source.contains("ProductRepository(database.productDao())"))
        assertTrue("must construct AiSalePreviewResolver", source.contains("AiSalePreviewResolver("))
        assertTrue("must use customerRepository search reference", source.contains("customerRepository::search"))
        assertTrue("must use productRepository search reference", source.contains("productRepository::search"))
        assertTrue("must pass salePreviewResolver to CuentivoApp", source.contains("salePreviewResolver ="))
    }

    @Test
    fun `main activity constructs AiSaleExecutionService using local repository and sync queue and passes confirmSale to CuentivoApp`() {
        val source = File("src/main/java/com/example/organizationlabs/MainActivity.kt").readText()
        assertTrue("must construct SaleRepository", source.contains("SaleRepository(database.financialDao())"))
        assertTrue("must construct AiSaleExecutionService", source.contains("AiSaleExecutionService("))
        assertTrue("must wire registerCompositeAgainstPreview", source.contains("saleRepository::registerCompositeAgainstPreview"))
        assertTrue("must wire storedBundle", source.contains("saleRepository::storedBundle"))
        assertTrue("must enqueue CUSTOMER", source.contains("\"CUSTOMER\""))
        assertTrue("must enqueue PRODUCT", source.contains("\"PRODUCT\""))
        assertTrue("must enqueue SALE", source.contains("\"SALE\""))
        assertTrue("must enqueue SALE_ITEM", source.contains("\"SALE_ITEM\""))
        assertTrue("must enqueue MOVEMENT", source.contains("\"MOVEMENT\""))
        assertTrue("must enqueue PAYMENT", source.contains("\"PAYMENT\""))
        assertTrue("must enqueue PAYMENT_ALLOCATION", source.contains("\"PAYMENT_ALLOCATION\""))
        assertTrue("must pass confirmSale to CuentivoApp", source.contains("confirmSale ="))
        assertTrue("must validate active business ID from DashboardUiState.Content", source.contains("(state as? DashboardUiState.Content)?.businessId"))
        assertTrue("must reject null or mismatched businessId or blank user", source.contains("activeBusinessId == null || activeBusinessId != request.businessId || currentUserId.isBlank()"))
        assertTrue("must return Rejected on mismatch", source.contains("AiSaleExecutionResult.Rejected"))
        assertTrue("must check customer limit when new customer is created", source.contains("FeatureAccessManager.canAddCustomer(tier, customers.size)"))
        assertTrue("must check registered sale count", source.contains("database.financialDao().registeredSaleCount(request.businessId)"))
        assertTrue("must check sale limit", source.contains("FeatureAccessManager.canRegisterSale(tier, salesSoFar)"))
        assertTrue("must assign blocked state on customer limit blocked", source.contains("blocked = customerDecision"))
        assertTrue("must assign blocked state on sale limit blocked", source.contains("blocked = saleDecision"))
        assertTrue("must return LimitReached on limit blocked", source.contains("AiSaleExecutionResult.LimitReached"))
    }

    @Test
    fun `plan limit evaluation blocks new customer and sales when free limit reached and allows when pro`() {
        val freeTier = com.cuentivo.app.domain.SubscriptionTier.FREE
        val proTier = com.cuentivo.app.domain.SubscriptionTier.PRO

        val customerBlocked = com.cuentivo.app.domain.FeatureAccessManager.canAddCustomer(freeTier, 5)
        assertTrue("5 customers in Free must be blocked", customerBlocked is com.cuentivo.app.domain.AccessDecision.Blocked)

        val customerAllowedUnder = com.cuentivo.app.domain.FeatureAccessManager.canAddCustomer(freeTier, 4)
        assertEquals("4 customers in Free must be allowed", com.cuentivo.app.domain.AccessDecision.Allowed, customerAllowedUnder)

        val customerAllowedPro = com.cuentivo.app.domain.FeatureAccessManager.canAddCustomer(proTier, 100)
        assertEquals("Pro tier must allow unlimited customers", com.cuentivo.app.domain.AccessDecision.Allowed, customerAllowedPro)

        val saleBlocked = com.cuentivo.app.domain.FeatureAccessManager.canRegisterSale(freeTier, 30)
        assertTrue("30 sales in Free must be blocked", saleBlocked is com.cuentivo.app.domain.AccessDecision.Blocked)

        val saleAllowedUnder = com.cuentivo.app.domain.FeatureAccessManager.canRegisterSale(freeTier, 29)
        assertEquals("29 sales in Free must be allowed", com.cuentivo.app.domain.AccessDecision.Allowed, saleAllowedUnder)

        val saleAllowedPro = com.cuentivo.app.domain.FeatureAccessManager.canRegisterSale(proTier, 1000)
        assertEquals("Pro tier must allow unlimited sales", com.cuentivo.app.domain.AccessDecision.Allowed, saleAllowedPro)
    }

    @Test
    fun `AiSaleExecutionService has single constructor and does not expose deprecated 10-arg bridge`() {
        val nonSyntheticConstructors = com.cuentivo.app.ai.AiSaleExecutionService::class.java.declaredConstructors
            .filter { !it.isSynthetic && !it.parameterTypes.any { param -> param.name.contains("DefaultConstructorMarker") } }
        assertEquals("AiSaleExecutionService must have exactly 1 constructor", 1, nonSyntheticConstructors.size)
        val constructor = nonSyntheticConstructors.first()
        assertEquals("Primary constructor must have 6 parameters", 6, constructor.parameterTypes.size)
    }

    @Test
    fun `main activity constructs AiBusinessQueryResolver once using the five existing DAO method references and passes it to CuentivoApp`() {
        val source = File("src/main/java/com/example/organizationlabs/MainActivity.kt").readText()
        assertTrue("must construct AiBusinessQueryResolver", source.contains("AiBusinessQueryResolver("))
        assertTrue("must use customerRepository search reference", source.contains("searchCustomers = customerRepository::search"))
        assertTrue("must use customerBalance reference", source.contains("customerBalance = database.financialDao()::customerBalanceCents"))
        assertTrue("must use activeWithBalances reference", source.contains("customersWithBalances = database.customerDao()::activeWithBalances"))
        assertTrue("must use salesTotal reference", source.contains("salesTotal = database.financialDao()::salesTotalCents"))
        assertTrue("must use collectedTotal reference", source.contains("collectedTotal = database.financialDao()::collectedTotalCents"))
        assertTrue("must pass businessQueryResolver to CuentivoApp", source.contains("businessQueryResolver = aiBusinessQueryResolver::resolve"))
    }

    @Test
    fun `cuentivo app derives active business id exclusively from DashboardUiState Content and passes dependencies`() {
        val source = File("src/main/java/com/cuentivo/app/ui/CuentivoApp.kt").readText()
        assertTrue(
            "businessId must be derived from DashboardUiState.Content",
            source.contains("(state as? DashboardUiState.Content)?.businessId"),
        )
        assertTrue(
            "screen must receive paymentPreviewResolver and activeBusinessId",
            source.contains("paymentPreviewResolver = paymentPreviewResolver") &&
                source.contains("activeBusinessId = activeBusinessId"),
        )
        assertTrue(
            "screen must receive salePreviewResolver and confirmSale",
            source.contains("salePreviewResolver = salePreviewResolver") &&
                source.contains("confirmSale = confirmSale"),
        )
        assertTrue(
            "screen must receive businessQueryResolver",
            source.contains("businessQueryResolver = businessQueryResolver"),
        )
        assertTrue(
            "CuentivoApp parameter confirmPayment must be nullable suspend callback",
            source.contains("confirmPayment: (suspend (AiPaymentConfirmationRequest) -> AiPaymentExecutionResult)? = null"),
        )
        assertTrue(
            "CuentivoApp parameter confirmSale must be nullable suspend callback",
            source.contains("confirmSale: (suspend (AiSaleConfirmationRequest) -> AiSaleExecutionResult)? = null"),
        )
        assertTrue(
            "CuentivoApp parameter businessQueryResolver must be nullable suspend callback",
            source.contains("businessQueryResolver: (suspend (businessId: String, proposal: com.cuentivo.app.ai.BusinessQueryProposal, choice: com.cuentivo.app.ai.AiEntityChoice?) -> com.cuentivo.app.ai.AiBusinessQueryResult)? = null"),
        )
        assertTrue(
            "screen must receive confirmPayment",
            source.contains("confirmPayment = confirmPayment"),
        )
    }

    @Test
    fun `screen accepts nullable resolver and active business id and resolves on IO only for payment proposal`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue(
            "screen parameter paymentPreviewResolver must be nullable",
            source.contains("paymentPreviewResolver: (suspend (businessId: String, proposal: RecordPaymentProposal) -> AiPaymentPreviewResult)? = null"),
        )
        assertTrue(
            "screen parameter activeBusinessId must be nullable",
            source.contains("activeBusinessId: String? = null"),
        )
        assertTrue(
            "screen must invoke paymentPreviewResolver for RecordPaymentProposal",
            source.contains("paymentPreviewResolver(") && source.contains("RecordPaymentProposal"),
        )
        assertTrue(
            "screen must run resolver on Dispatchers.IO",
            source.contains("withContext(Dispatchers.IO)"),
        )
        assertTrue(
            "screen must update state via onPaymentPreviewResult",
            source.contains("AiDebugLabMachine.onPaymentPreviewResult("),
        )
    }

    @Test
    fun `chat message row renders all sealed payment variants with confirmation action only on preview`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("must handle AssistantPaymentPreview", source.contains("is AiChatMessage.AssistantPaymentPreview"))
        assertTrue("must handle AssistantPaymentCustomerNotFound", source.contains("AiChatMessage.AssistantPaymentCustomerNotFound"))
        assertTrue("must handle AssistantPaymentCustomerSelection", source.contains("is AiChatMessage.AssistantPaymentCustomerSelection"))
        assertTrue("must handle AssistantPaymentBlocked", source.contains("is AiChatMessage.AssistantPaymentBlocked"))
        assertTrue("must handle AssistantPaymentRecorded", source.contains("is AiChatMessage.AssistantPaymentRecorded"))
        assertTrue("must handle AssistantPaymentStale", source.contains("AiChatMessage.AssistantPaymentStale"))
        assertTrue("must handle AssistantPaymentRejected", source.contains("AiChatMessage.AssistantPaymentRejected"))
        assertTrue("must render draft note", source.contains("ai_payment_preview_draft_note"))
        assertTrue("must offer confirm action", source.contains("ai_payment_confirm_action"))
        assertTrue("must offer cancel action", source.contains("ai_payment_confirm_cancel"))
    }

    @Test
    fun `no confirm action exists for blocked, unresolved, unavailable or arbitrary proposal messages`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        val chatMessageRowBlock = source.substring(
            source.indexOf("private fun ChatMessageRow("),
            source.indexOf("private fun ProposalPreviewContent("),
        )
        val blockedBlock = chatMessageRowBlock.substring(
            chatMessageRowBlock.indexOf("is AiChatMessage.AssistantPaymentBlocked"),
            chatMessageRowBlock.indexOf("is AiChatMessage.AssistantPaymentRecorded"),
        )
        assertFalse("blocked has no confirm action", blockedBlock.contains("ai_payment_confirm_action"))

        val notFoundBlock = chatMessageRowBlock.substring(
            chatMessageRowBlock.indexOf("AiChatMessage.AssistantPaymentCustomerNotFound"),
            chatMessageRowBlock.indexOf("is AiChatMessage.AssistantPaymentCustomerSelection"),
        )
        assertFalse("not found has no confirm action", notFoundBlock.contains("ai_payment_confirm_action"))

        val selectionBlock = chatMessageRowBlock.substring(
            chatMessageRowBlock.indexOf("is AiChatMessage.AssistantPaymentCustomerSelection"),
            chatMessageRowBlock.indexOf("is AiChatMessage.AssistantPaymentBlocked"),
        )
        assertFalse("selection has no confirm action", selectionBlock.contains("ai_payment_confirm_action"))

        val proposalBlock = chatMessageRowBlock.substring(
            chatMessageRowBlock.indexOf("is AiChatMessage.AssistantProposal"),
            chatMessageRowBlock.indexOf("is AiChatMessage.AssistantPaymentPreview"),
        )
        assertFalse("proposal has no confirm action", proposalBlock.contains("ai_payment_confirm_action"))
    }

    @Test
    fun `no callable or Gemini invocation occurs during confirmation`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        val confirmCallIndex = source.indexOf("confirmPayment(outcome.request)")
        assertTrue("confirmPayment is called with local request", confirmCallIndex != -1)
        val surroundingBlock = source.substring(
            source.lastIndexOf("onConfirmPayment = if", confirmCallIndex),
            source.indexOf("onCancelPayment =", confirmCallIndex),
        )
        assertFalse("confirmation must not invoke transport", surroundingBlock.contains("transport"))
        assertFalse("confirmation must not invoke turn policy", surroundingBlock.contains("AiDebugLabTurnPolicy"))
        assertFalse("confirmation must not invoke Gemini", surroundingBlock.contains("gemini", ignoreCase = true))
    }

    @Test
    fun `confirmPayment rethrows CancellationException and falls back to Unavailable only for other exceptions`() {
        val screenSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        val confirmCallIndex = screenSource.indexOf("confirmPayment(outcome.request)")
        assertTrue("confirmPayment is called", confirmCallIndex != -1)
        val confirmationBlock = screenSource.substring(
            screenSource.lastIndexOf("val result = withContext(Dispatchers.IO)", confirmCallIndex),
            screenSource.indexOf("model = AiDebugLabMachine.onPaymentExecutionResult", confirmCallIndex),
        )
        assertTrue("must explicitly catch CancellationException", confirmationBlock.contains("catch (e: CancellationException)"))
        assertTrue("must rethrow CancellationException", confirmationBlock.contains("throw e"))
        assertTrue("must catch Exception for non-cancellation errors", confirmationBlock.contains("catch (_: Exception)"))
        assertFalse("must not catch Throwable to avoid masking JVM Errors", confirmationBlock.contains("catch (_: Throwable)"))
        assertTrue("must map non-cancellation errors to Unavailable", confirmationBlock.contains("AiPaymentExecutionResult.Unavailable"))
        assertFalse("must not use blind runCatching around confirmPayment", confirmationBlock.contains("runCatching"))
    }

    @Test
    fun `confirmation key and payment ID are not shown in UI or persisted in daily chat`() {
        val screenSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertFalse("confirmationKey is never displayed in UI text", screenSource.contains(".confirmationKey"))
        assertFalse("paymentId is never displayed in UI text", screenSource.contains(".paymentId"))

        val modelSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt").readText()
        val historyLoadedBlock = modelSource.substring(
            modelSource.indexOf("fun onHistoryLoaded"),
            modelSource.indexOf("fun onOutOfScope"),
        )
        assertFalse("history loader never restores payment confirmation", historyLoadedBlock.contains("PaymentConfirmation"))
        assertFalse("history loader never restores payment recorded", historyLoadedBlock.contains("AssistantPaymentRecorded"))
    }

    @Test
    fun `payment preview string resources maintain key parity across spanish and english`() {
        val esXml = File("src/main/res/values/strings.xml").readText()
        val enXml = File("src/main/res/values-en/strings.xml").readText()
        val requiredKeys = listOf(
            "ai_payment_preview_customer_not_found",
            "ai_payment_preview_selection_required",
            "ai_payment_preview_current_balance",
            "ai_payment_preview_proposed_amount",
            "ai_payment_preview_projected_balance",
            "ai_payment_preview_draft_note",
            "ai_payment_preview_blocked_no_debt",
            "ai_payment_preview_blocked_no_debt_with_name",
            "ai_payment_preview_blocked_exceeds_debt",
            "ai_payment_preview_blocked_exceeds_debt_no_details",
            "ai_payment_confirm_action",
            "ai_payment_confirm_cancel",
            "ai_payment_recorded_success",
            "ai_payment_stale_message",
            "ai_payment_rejected_message",
        )
        for (key in requiredKeys) {
            assertTrue("Spanish strings must contain $key", esXml.contains("name=\"$key\""))
            assertTrue("English strings must contain $key", enXml.contains("name=\"$key\""))
        }
    }

    @Test
    fun `payment and query blocked states render distinct actionable messages without generic walls`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("must handle blocked with customer name", source.contains("ai_payment_preview_blocked_no_debt_with_name"))
        assertTrue("must handle blocked debt with no details fallback", source.contains("ai_payment_preview_blocked_exceeds_debt_no_details"))
        assertTrue("must handle invalid query date with actionable text", source.contains("ai_query_blocked_invalid_date"))
        assertTrue("must handle invalid query start after end", source.contains("ai_query_blocked_start_after_end"))
        assertTrue("must handle invalid query period too long", source.contains("ai_query_blocked_period_too_long"))
    }

    @Test
    fun `sale preview string resources maintain key parity across spanish and english`() {
        val esXml = File("src/main/res/values/strings.xml").readText()
        val enXml = File("src/main/res/values-en/strings.xml").readText()
        val requiredKeys = listOf(
            "ai_sale_preview_customer_selection_prompt",
            "ai_sale_preview_product_selection_prompt",
            "ai_sale_choice_create_new",
            "ai_entity_badge_existing",
            "ai_entity_badge_new",
            "ai_sale_preview_new_product_habitual_price",
            "ai_sale_preview_candidate_with_price",
            "ai_sale_preview_customer_format",
            "ai_sale_preview_total",
            "ai_sale_preview_initial_payment",
            "ai_sale_preview_remainder",
            "ai_sale_preview_blocked_overpayment",
            "ai_sale_preview_blocked_overpayment_no_details",
            "ai_sale_preview_blocked_overflow",
            "ai_sale_preview_blocked_invalid_quantity",
            "ai_sale_preview_blocked_invalid_price",
            "ai_sale_preview_blocked_inconsistent_new_product_price",
            "ai_sale_preview_blocked_invalid_line",
            "ai_sale_confirm_action",
            "ai_sale_confirm_cancel",
            "ai_sale_recorded_success",
            "ai_sale_stale_message",
            "ai_sale_rejected_message",
            "ai_sale_limit_reached_message",
        )
        for (key in requiredKeys) {
            assertTrue("Spanish strings must contain $key", esXml.contains("name=\"$key\""))
            assertTrue("English strings must contain $key", enXml.contains("name=\"$key\""))
        }
    }

    @Test
    fun `sale blocked states render distinct actionable messages without generic walls`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("must handle overpayment with details", source.contains("ai_sale_preview_blocked_overpayment"))
        assertTrue("must handle overpayment no details fallback", source.contains("ai_sale_preview_blocked_overpayment_no_details"))
        assertTrue("must handle overflow with actionable text", source.contains("ai_sale_preview_blocked_overflow"))
        assertTrue("must handle invalid quantity with actionable text", source.contains("ai_sale_preview_blocked_invalid_quantity"))
        assertTrue("must handle invalid price with actionable text", source.contains("ai_sale_preview_blocked_invalid_price"))
        assertTrue("must handle inconsistent price with actionable text", source.contains("ai_sale_preview_blocked_inconsistent_new_product_price"))
    }

    @Test
    fun `clarification string resources maintain key parity across spanish and english and avoid raw enum names`() {
        val esXml = File("src/main/res/values/strings.xml").readText()
        val enXml = File("src/main/res/values-en/strings.xml").readText()
        val requiredKeys = listOf(
            "ai_clarification_reason_ambiguous_customer",
            "ai_clarification_reason_ambiguous_product",
            "ai_clarification_reason_missing_customer",
            "ai_clarification_reason_missing_product",
            "ai_clarification_reason_missing_amount",
            "ai_clarification_reason_unknown_input",
            "ai_clarification_missing_slots_format",
            "ai_slot_customer",
            "ai_slot_product",
            "ai_slot_amount",
            "ai_slot_quantity",
            "ai_slot_period",
        )
        for (key in requiredKeys) {
            assertTrue("Spanish strings must contain $key", esXml.contains("name=\"$key\""))
            assertTrue("English strings must contain $key", enXml.contains("name=\"$key\""))
        }

        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        val clarificationBlock = source.substring(
            source.indexOf("is AiChatMessage.AssistantClarification"),
            source.indexOf("is AiChatMessage.AssistantProposal"),
        )
        assertFalse("must not use raw enum name for clarification reason", clarificationBlock.contains(".name"))
        assertTrue("must handle all clarification reasons", clarificationBlock.contains("ai_clarification_reason_unknown_input"))
        assertTrue("must handle slots formatting", clarificationBlock.contains("ai_clarification_missing_slots_format"))
    }

    @Test
    fun `confirmSale rethrows CancellationException and falls back to Unavailable only for other exceptions`() {
        val screenSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        val confirmCallIndex = screenSource.indexOf("confirmSale(outcome.request)")
        assertTrue("confirmSale is called", confirmCallIndex != -1)
        val confirmationBlock = screenSource.substring(
            screenSource.lastIndexOf("val result = withContext(Dispatchers.IO)", confirmCallIndex),
            screenSource.indexOf("model = AiDebugLabMachine.onSaleExecutionResult", confirmCallIndex),
        )
        assertTrue("must explicitly catch CancellationException", confirmationBlock.contains("catch (e: CancellationException)"))
        assertTrue("must rethrow CancellationException", confirmationBlock.contains("throw e"))
        assertTrue("must catch Exception for non-cancellation errors", confirmationBlock.contains("catch (_: Exception)"))
        assertFalse("must not catch Throwable to avoid masking JVM Errors", confirmationBlock.contains("catch (_: Throwable)"))
        assertTrue("must map non-cancellation errors to Unavailable", confirmationBlock.contains("AiSaleExecutionResult.Unavailable"))
        assertFalse("must not use blind runCatching around confirmSale", confirmationBlock.contains("runCatching"))
    }

    @Test
    fun `no callable or Gemini invocation occurs during sale confirmation`() {
        val source = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        val confirmCallIndex = source.indexOf("confirmSale(outcome.request)")
        assertTrue("confirmSale is called with local request", confirmCallIndex != -1)
        val surroundingBlock = source.substring(
            source.lastIndexOf("onConfirmSale = if", confirmCallIndex),
            source.indexOf("onSelectCustomerCandidate =", confirmCallIndex),
        )
        assertFalse("sale confirmation must not invoke transport", surroundingBlock.contains("transport"))
        assertFalse("sale confirmation must not invoke turn policy", surroundingBlock.contains("AiDebugLabTurnPolicy"))
        assertFalse("sale confirmation must not invoke Gemini", surroundingBlock.contains("gemini", ignoreCase = true))
    }

    @Test
    fun `resolvers rethrow CancellationException and map only other Exceptions to Unavailable without runCatching`() {
        val screenSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()

        val paymentResolverIndex = screenSource.indexOf("paymentPreviewResolver(activeBusinessId, paymentProposal)")
        assertTrue("payment resolver is called", paymentResolverIndex != -1)
        val paymentResolutionBlock = screenSource.substring(
            screenSource.lastIndexOf("val resolutionResult = if", paymentResolverIndex),
            screenSource.indexOf("model = AiDebugLabMachine.onPaymentPreviewResult", paymentResolverIndex),
        )
        assertTrue("payment resolver explicitly catches CancellationException", paymentResolutionBlock.contains("catch (e: CancellationException)"))
        assertTrue("payment resolver rethrows CancellationException", paymentResolutionBlock.contains("throw e"))
        assertTrue("payment resolver catches Exception", paymentResolutionBlock.contains("catch (_: Exception)"))
        assertFalse("payment resolver must not catch Throwable", paymentResolutionBlock.contains("catch (_: Throwable)"))
        assertTrue("payment resolver maps to Unavailable", paymentResolutionBlock.contains("AiPaymentPreviewResult.Unavailable"))
        assertFalse("payment resolver must not use runCatching", paymentResolutionBlock.contains("runCatching"))

        val saleResolverIndex = screenSource.indexOf("salePreviewResolver(activeBusinessId, saleProposal,")
        assertTrue("sale resolver is called", saleResolverIndex != -1)
        val saleResolutionBlock = screenSource.substring(
            screenSource.lastIndexOf("val resolutionResult = if", saleResolverIndex),
            screenSource.indexOf("model = AiDebugLabMachine.onSalePreviewResult", saleResolverIndex),
        )
        assertTrue("sale resolver explicitly catches CancellationException", saleResolutionBlock.contains("catch (e: CancellationException)"))
        assertTrue("sale resolver rethrows CancellationException", saleResolutionBlock.contains("throw e"))
        assertTrue("sale resolver catches Exception", saleResolutionBlock.contains("catch (_: Exception)"))
        assertFalse("sale resolver must not catch Throwable", saleResolutionBlock.contains("catch (_: Throwable)"))
        assertTrue("sale resolver maps to Unavailable", saleResolutionBlock.contains("AiSalePreviewResult.Unavailable"))
        assertFalse("sale resolver must not use runCatching", saleResolutionBlock.contains("runCatching"))
    }

    @Test
    fun `confirmation key and sale ID are not shown in UI or persisted in daily chat`() {
        val screenSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertFalse("sale confirmationKey is never displayed in UI text", screenSource.contains(".confirmationKey"))
        assertFalse("saleId is never displayed in UI text", screenSource.contains(".saleId"))

        val modelSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabModel.kt").readText()
        val historyLoadedBlock = modelSource.substring(
            modelSource.indexOf("fun onHistoryLoaded"),
            modelSource.indexOf("fun onOutOfScope"),
        )
        assertFalse("history loader never restores sale confirmation", historyLoadedBlock.contains("SaleConfirmation"))
        assertFalse("history loader never restores sale recorded", historyLoadedBlock.contains("AssistantSaleRecorded"))
    }

    @Test
    fun `confirmation and resolution UI callbacks require activeBusinessId isNotBlank`() {
        val screenSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("onConfirmPayment requires isNotBlank", screenSource.contains("onConfirmPayment = if (isCurrentPaymentPreview && isHydrated && confirmPayment != null && activeBusinessId?.isNotBlank() == true)"))
        assertTrue("onConfirmSale requires isNotBlank", screenSource.contains("onConfirmSale = if (isCurrentSalePreview && isHydrated && confirmSale != null && activeBusinessId?.isNotBlank() == true)"))
        assertTrue("payment resolution requires isNotBlank", screenSource.contains("paymentPreviewResolver != null && activeBusinessId?.isNotBlank() == true"))
        assertTrue("sale resolution requires isNotBlank", screenSource.contains("salePreviewResolver != null && activeBusinessId?.isNotBlank() == true"))
        assertTrue("query resolution requires isNotBlank", screenSource.contains("businessQueryResolver != null && activeBusinessId?.isNotBlank() == true"))
    }

    @Test
    fun `chat message row renders natural query results with localized strings and candidate selection`() {
        val screenSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        assertTrue("must handle AssistantQueryAnswer", screenSource.contains("is AiChatMessage.AssistantQueryAnswer"))
        assertTrue("must handle CustomerBalance", screenSource.contains("is AiBusinessQueryResult.Answer.CustomerBalance"))
        assertTrue("must handle CustomersWithDebt", screenSource.contains("is AiBusinessQueryResult.Answer.CustomersWithDebt"))
        assertTrue("must handle SalesForPeriod", screenSource.contains("is AiBusinessQueryResult.Answer.SalesForPeriod"))
        assertTrue("must handle CustomerSelectionRequired", screenSource.contains("is AiBusinessQueryResult.CustomerSelectionRequired"))
        assertTrue("must handle CustomerNotFound", screenSource.contains("is AiBusinessQueryResult.CustomerNotFound"))
        assertTrue("must handle Blocked", screenSource.contains("is AiBusinessQueryResult.Blocked"))
        assertTrue("must format total debt for all customers in business", screenSource.contains("ai_query_total_debt_all_customers"))
        assertTrue("must render empty sales period notice", screenSource.contains("ai_query_sales_period_empty"))
        assertTrue("must render empty debtors notice", screenSource.contains("ai_query_no_debtors"))
        assertTrue("must format debtor row with string resource", screenSource.contains("ai_query_debtor_row_format"))
        assertTrue("must format proposal period with string resource", screenSource.contains("ai_query_proposal_period_format"))
    }

    @Test
    fun `business query resolution handles cancellation and maps exceptions safely`() {
        val screenSource = File("src/main/java/com/cuentivo/app/ui/ai/AiDebugLabScreen.kt").readText()
        val queryResolverIndex = screenSource.indexOf("businessQueryResolver(activeBusinessId, queryProposal, null)")
        assertTrue("query resolver is called in onSubmit", queryResolverIndex != -1)
        val queryResolutionBlock = screenSource.substring(
            screenSource.lastIndexOf("val queryResolutionResult = if", queryResolverIndex),
            screenSource.indexOf("model = AiDebugLabMachine.onQueryResult", queryResolverIndex),
        )
        assertTrue("query resolver explicitly catches CancellationException", queryResolutionBlock.contains("catch (e: CancellationException)"))
        assertTrue("query resolver rethrows CancellationException", queryResolutionBlock.contains("throw e"))
        assertTrue("query resolver catches Exception", queryResolutionBlock.contains("catch (_: Exception)"))
        assertFalse("query resolver must not catch Throwable", queryResolutionBlock.contains("catch (_: Throwable)"))
        assertTrue("query resolver maps to Unavailable", queryResolutionBlock.contains("AiBusinessQueryResult.Unavailable"))
        assertFalse("query resolver must not use runCatching", queryResolutionBlock.contains("runCatching"))
    }

    @Test
    fun `formatCents formats zero, positive, negative and Long MIN_VALUE safely without overflow`() {
        assertEquals("$0.00", formatCents(0L))
        assertEquals("$500.00", formatCents(50000L))
        assertEquals("$-15.00", formatCents(-1500L))
        assertEquals("$15.00", formatCents(-1500L, absolute = true))
        assertEquals("$92233720368547758.08", formatCents(Long.MIN_VALUE, absolute = true))
        assertEquals("$-92233720368547758.08", formatCents(Long.MIN_VALUE, absolute = false))
    }

    @Test
    fun `business query code path performs no writes, mutations, outbox enqueuing or audit logging`() {
        val resolverSource = File("src/main/java/com/cuentivo/app/ai/AiBusinessQuery.kt").readLines()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/*") }
            .joinToString("\n")
        assertFalse("must not reference syncQueue", resolverSource.contains("syncQueue") || resolverSource.contains("SyncQueue"))
        assertFalse("must not reference audit", resolverSource.contains("audit") || resolverSource.contains("Audit"))
        assertFalse("must not reference insert", resolverSource.contains(".insert("))
        assertFalse("must not reference update", resolverSource.contains(".update("))
        assertFalse("must not reference delete", resolverSource.contains(".delete("))
        assertFalse("must not reference outbox", resolverSource.contains("outbox"))
        assertFalse("must not import Room", resolverSource.contains("androidx.room"))
        assertFalse("must not import Firestore", resolverSource.contains("com.google.firebase.firestore"))
    }

    @Test
    fun `MainActivity handles exactly orientation and screenSize config changes in manifest to preserve in memory state`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activityBlock = manifest.substringAfter("<activity").substringBefore("</activity>")
        assertTrue("MainActivity must be declared", activityBlock.contains("android:name=\".MainActivity\""))
        assertTrue("MainActivity must contain configChanges attribute", activityBlock.contains("android:configChanges=\""))
        val configChangesRaw = activityBlock.substringAfter("android:configChanges=\"").substringBefore("\"")
        val configChangesSet = configChangesRaw.split("|").map { it.trim() }.toSet()
        assertEquals(
            "MainActivity configChanges must contain exactly orientation and screenSize",
            setOf("orientation", "screenSize"),
            configChangesSet,
        )
    }
}
