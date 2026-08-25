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
            "CuentivoApp parameter confirmPayment must be nullable suspend callback",
            source.contains("confirmPayment: (suspend (AiPaymentConfirmationRequest) -> AiPaymentExecutionResult)? = null"),
        )
        assertTrue(
            "CuentivoApp parameter confirmSale must be nullable suspend callback",
            source.contains("confirmSale: (suspend (AiSaleConfirmationRequest) -> AiSaleExecutionResult)? = null"),
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
            "ai_payment_preview_blocked_exceeds_debt",
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
            "ai_sale_preview_blocked_overflow",
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
}
