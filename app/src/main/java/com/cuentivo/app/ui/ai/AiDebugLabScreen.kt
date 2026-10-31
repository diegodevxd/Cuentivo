package com.cuentivo.app.ui.ai

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.res.painterResource
import com.cuentivo.app.ai.AndroidVoiceRecognizer
import com.cuentivo.app.ai.VoiceCaptureMachine
import com.cuentivo.app.ai.VoiceCaptureModel
import com.cuentivo.app.ai.VoiceCaptureState
import com.cuentivo.app.ai.VoiceRecognizer
import com.cuentivo.app.ai.VoiceUnavailableReason
import com.cuentivo.app.ai.normalizeRmsDb
import com.cuentivo.app.R
import com.cuentivo.app.ai.AiBusinessQueryResult
import com.cuentivo.app.ai.AiDailyMessageKind
import com.cuentivo.app.ai.AiInterpretRequest
import com.cuentivo.app.ai.AiPaymentBlockReason
import com.cuentivo.app.ai.AiPaymentConfirmationRequest
import com.cuentivo.app.ai.AiPaymentExecutionResult
import com.cuentivo.app.ai.AiPaymentPreviewResult
import com.cuentivo.app.ai.AiProposal
import com.cuentivo.app.ai.AiSaleBlockReason
import com.cuentivo.app.ai.AiSaleConfirmationRequest
import com.cuentivo.app.ai.AiSaleExecutionResult
import com.cuentivo.app.ai.AiSalePreviewResult
import com.cuentivo.app.ai.AiResolvedSalePreview
import com.cuentivo.app.ai.BusinessQueryProposal
import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.CreateSaleProposal
import com.cuentivo.app.ai.RecordPaymentProposal
import com.cuentivo.app.ai.capture.AiCameraCaptureStore
import com.cuentivo.app.ai.capture.AiCapturePolicy
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ui.transaction.DueOption
import com.cuentivo.app.ui.transaction.NeoChoiceChip
import com.cuentivo.app.ai.ledger.LedgerExtractionCallResult
import com.cuentivo.app.ai.ledger.AiLedgerImagePolicy
import com.cuentivo.app.ai.ledger.LedgerImagePreparer
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation
import com.cuentivo.app.ai.capture.AiCaptureStore
import com.cuentivo.app.ai.capture.AiOcrEngine
import com.cuentivo.app.data.local.AiDailyChatRepository
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only pill that opens the lab; hidden on release builds via [debugAiTransport]. */
@Composable
fun AiDebugLabEntryButton(
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(50),
            ),
    ) {
        TextButton(onClick = onOpen) {
            Text(stringResource(R.string.ai_debug_lab_entry))
        }
    }
}

/**
 * Survives Activity recreation while the camera app is in front.
 *
 * The camera runs in another process, so this one can be destroyed while it is on screen. A plain
 * `remember` would forget where the photo was being written and the returning result would be
 * dropped even though the file landed on disk.
 */
private val CaptureUriSaver = Saver<Uri?, String>(
    save = { it?.toString() },
    restore = { Uri.parse(it) },
)

/**
 * Saves and restores the review under way across Activity recreation.
 *
 * The saved value is always a Bundle, never null: a null save would make the state itself vanish
 * from the registry, and the screen needs the slot to exist so an ended import writes "empty"
 * over a previously saved one. An empty Bundle restores as null — no review to bring back.
 *
 * The heavy, derivable half of the review — balances, candidates, registered rows — is
 * deliberately not here: it is reloaded from Room by the restore effect, and a Bundle big
 * enough to hold it would exceed the transaction limit and kill the app.
 */
private val LedgerReviewStateSaver = Saver<com.cuentivo.app.ui.ai.LedgerReviewSaver.SavedLedgerReview?, Bundle>(
    save = { it?.let(LedgerReviewSaver::toBundle) ?: Bundle() },
    restore = { bundle -> if (bundle.isEmpty) null else LedgerReviewSaver.fromBundle(bundle) },
)

/**
 * The Room-loaded context a review needs: what each known customer already owes, the candidates a
 * misread name can be matched against, the catalogue, and what is already registered around the
 * page's dates. All of it derivable, none of it saved — it is reloaded after every restore.
 */
internal data class LedgerReviewContext(
    val existingCustomerBalances: Map<String, Long>,
    val customerCandidates: List<com.cuentivo.app.domain.CustomerCandidate>,
    val productCandidates: List<com.cuentivo.app.domain.ProductCandidate>,
    val registeredOperations: List<com.cuentivo.app.domain.RegisteredOperationProjection>,
)

/**
 * AI debug text lab with optional explicit payment and sale confirmation. Edits text, sends ONE request
 * through the injected transport and shows the safe result: unavailable, clarification, proposal,
 * or a typed preview.
 *
 * Confirmation is human-initiated and explicit: [confirmPayment] and [confirmSale] are injected host callbacks;
 * this screen performs zero direct Room mutations, financial calculations, or model/transport
 * calls during confirmation. Generation tokens and single-flight states protect against replay
 * and late async responses. On release builds, the lab remains entirely disabled and unreachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDebugLabScreen(
    transport: AiDebugTransport,
    repository: AiDailyChatRepository,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    paymentPreviewResolver: (suspend (businessId: String, proposal: RecordPaymentProposal) -> AiPaymentPreviewResult)? = null,
    salePreviewResolver: (suspend (businessId: String, proposal: CreateSaleProposal, choices: com.cuentivo.app.ai.AiSaleResolutionChoices) -> AiSalePreviewResult)? = null,
    businessQueryResolver: (suspend (businessId: String, proposal: BusinessQueryProposal, choice: com.cuentivo.app.ai.AiEntityChoice?) -> com.cuentivo.app.ai.AiBusinessQueryResult)? = null,
    activeBusinessId: String? = null,
    confirmPayment: (suspend (AiPaymentConfirmationRequest) -> AiPaymentExecutionResult)? = null,
    confirmSale: (suspend (AiSaleConfirmationRequest) -> AiSaleExecutionResult)? = null,
    onOpenPaywall: (() -> Unit)? = null,
    voiceRecognizer: VoiceRecognizer? = null,
    extractLedgerPage: (suspend (requestId: String, imageBase64: String, imageMimeType: String) -> LedgerExtractionCallResult)? = null,
    ledgerBalancesResolver: (suspend (businessId: String) -> Map<String, Long>)? = null,
    /** The customers this business already has, so a misread name can be matched against them. */
    ledgerCustomersResolver: (suspend (businessId: String) -> List<com.cuentivo.app.domain.CustomerCandidate>)? = null,
    /** The catalogue, so an abbreviated product can be offered its full name. */
    ledgerProductsResolver: (suspend (businessId: String) -> List<com.cuentivo.app.domain.ProductCandidate>)? = null,
    /** What is already registered around this page's dates, so a second scan of the same sheet says so. */
    ledgerRegisteredResolver: (suspend (businessId: String, minEpochDay: Long, maxEpochDay: Long) -> List<com.cuentivo.app.domain.RegisteredOperationProjection>)? = null,
    confirmLedgerBatch: (suspend (businessId: String, importSessionId: String, operations: List<LedgerOperation>, pageDateEpochDay: Long?, dueDateDays: Long?, blockedLineIndices: Set<Int>, modelSaysTextMayBeMissing: Boolean, isPageCompletenessConfirmed: Boolean) -> com.cuentivo.app.domain.LedgerBatchConfirmation)? = null,
    /**
     * Where the local import metrics are written. Null in tests and anywhere Room is not wired:
     * metrics are diagnostics, and their absence must never be a reason the import fails.
     */
    ledgerImportMetricDao: com.cuentivo.app.data.local.AiLedgerImportMetricDao? = null,
    /**
     * Opened from "Tomar foto de mi libreta": the lab skips the chat and goes straight to the
     * camera/gallery chooser, because the person already said what they came to do. Consumed once
     * the chooser is shown, through [onStartLedgerImportConsumed].
     */
    startLedgerImport: Boolean = false,
    onStartLedgerImportConsumed: () -> Unit = {},
) {
    var model by remember { mutableStateOf(AiDebugLabModel()) }
    var voiceModel by remember { mutableStateOf(VoiceCaptureModel()) }
    var isHydrated by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showLedgerSourceSelector by remember { mutableStateOf(false) }
    var cameraUnavailable by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val effectiveVoiceRecognizer = voiceRecognizer ?: remember(context) { AndroidVoiceRecognizer(context) }
    val captureStore = remember(context) {
        com.cuentivo.app.ai.capture.AiCaptureStore(java.io.File(context.cacheDir, "ai-capture"))
    }
    val cameraStore = remember(context) { AiCameraCaptureStore(context) }
    // The photographed page stays on disk for as long as the review needs it: the crop beside each
    // row is the only way to check what the model read against what the notebook says. Deleted when
    // the import ends, whichever way it ends.
    var ledgerCaptureFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val ledgerCaptureFile: java.io.File? = ledgerCaptureFilePath?.let { java.io.File(it) }
    var ledgerSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    // The business the import belongs to, captured when it starts: reading activeBusinessId on the
    // way back would let a page taken for one business land on another.
    var ledgerBusinessId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLedgerUri by rememberSaveable(stateSaver = CaptureUriSaver) { mutableStateOf<Uri?>(null) }

    // The review under way survives the Activity being recreated (the system killing the process
    // in the background, a uiMode change, the camera app taking the front). Only the irrepeatable
    // half is saved — the reading of the page plus the corrections on top of it — because a Bundle
    // cannot hold the 552 products and blowing the transaction limit kills the app. The derivable
    // half (balances, candidates, registered rows) is reloaded from Room on the way back, and the
    // callable is never called again: the extraction result is right here.
    var savedLedgerReview by rememberSaveable(stateSaver = LedgerReviewStateSaver) {
        mutableStateOf<com.cuentivo.app.ui.ai.LedgerReviewSaver.SavedLedgerReview?>(null)
    }
    // The Room-loaded context of the review, kept out of the saved state on purpose. It is
    // reloaded after every restore, and while the review is live it is the same data the
    // LedgerReviewing state already froze.
    var ledgerReviewContext by remember {
        mutableStateOf<com.cuentivo.app.ui.ai.LedgerReviewContext?>(null)
    }

    // "Tomar foto de mi libreta" arrives with the intent already declared: open the camera/gallery
    // chooser as soon as there is a business to write to, instead of making the person find the
    // small link inside the chat. The same session-naming rule as the in-chat entry applies, so a
    // late result cannot land on a different review.
    LaunchedEffect(startLedgerImport, activeBusinessId, extractLedgerPage) {
        if (!startLedgerImport) return@LaunchedEffect
        if (extractLedgerPage == null || activeBusinessId.isNullOrBlank()) return@LaunchedEffect
        ledgerSessionId = java.util.UUID.randomUUID().toString()
        ledgerBusinessId = activeBusinessId
        showLedgerSourceSelector = true
        onStartLedgerImportConsumed()
    }

    DisposableEffect(effectiveVoiceRecognizer) {
        onDispose {
            effectiveVoiceRecognizer.destroy()
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, effectiveVoiceRecognizer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // Cancel active capture when leaving foreground (e.g. opening camera or backgrounding).
                // Do NOT cancel when state is PermissionRequired: Android system permission dialog causes
                // ON_PAUSE and cancelling here would revert to Idle before the user can grant permission.
                if (voiceModel.state is VoiceCaptureState.Listening || voiceModel.state is VoiceCaptureState.Processing) {
                    effectiveVoiceRecognizer.cancel()
                    voiceModel = VoiceCaptureMachine.onCancel(voiceModel)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(repository) {
        isHydrated = false
        val loaded = withContext(Dispatchers.IO) {
            runCatching { repository.openToday() }.getOrNull()
        }
        if (loaded != null) {
            model = AiDebugLabMachine.onHistoryLoaded(model, loaded.messages)
        }
        isHydrated = true
    }

    // While a review is live, its irrepeatable half is mirrored into the saved state on every
    // edit, so whatever the person has corrected is what comes back after a recreation.
    //
    // This effect only ever writes a review, never a null: a model that is still empty because
    // the restore has not run yet must not erase the saved state underneath it. The saved state
    // is cleared at the exact points where the import ends — cancel, close, or a successful
    // write — because those are the only moments "there is no review" is a fact rather than a
    // race.
    // SideEffect and not LaunchedEffect, and the difference is the whole point (Sol caught this).
    // A LaunchedEffect body is a coroutine: it is dispatched after the composition, so a recreation
    // landing in that window saves the PREVIOUS snapshot and the last correction the person made --
    // or a page that had just finished being read -- comes back missing. SideEffect runs as part of
    // applying the composition, before anything else can ask for the saved state, which closes the
    // window. A test that waits for Compose to go idle never sees it, which is why it took a reader.
    SideEffect {
        val reviewing = model.state as? AiDebugLabState.LedgerReviewing
        if (reviewing != null) {
            savedLedgerReview = LedgerReviewSaver.SavedLedgerReview(
                sessionId = reviewing.sessionId,
                businessId = reviewing.businessId,
                operations = reviewing.review.operations,
                originalOperations = reviewing.originalOperations,
                initialDuplicateCount = reviewing.initialDuplicateCount,
                discards = reviewing.review.discards,
                unreadTextRemaining = reviewing.review.unreadTextRemaining,
                pageIncomplete = reviewing.review.pageIncomplete,
                pageDateEpochDay = reviewing.review.pageDateEpochDay,
                todayEpochDay = reviewing.review.todayEpochDay,
                detectedPageDate = reviewing.review.detectedPageDate,
                isPageCompletenessConfirmed = reviewing.isPageCompletenessConfirmed,
                pageType = reviewing.review.pageType,
                pageTotals = reviewing.review.pageTotals,
            )
        }
    }

    // Restores a review after Activity recreation. This is the path that used to lose the whole
    // draft: the model came back empty, the photo survived on disk, and the only way forward was
    // reading the page again — another call to the callable, which is paid. Here the extraction
    // result comes back from the saved state and only the derivable context is reloaded from
    // Room. The callable is not called, not even once: this effect is the reason the defect
    // existed, and re-extracting "just in case" would reintroduce it wearing another hat.
    //
    // It waits for the chat history to hydrate first, so the two effects cannot race: hydration
    // runs on every composition, and a restore landing before it would be paved over by the
    // transcript load the moment it finished.
    LaunchedEffect(savedLedgerReview, isHydrated) {
        val saved = savedLedgerReview ?: return@LaunchedEffect
        if (!isHydrated) return@LaunchedEffect
        // A review already in the model means this is not a restore but a normal recomposition
        // (the mirror effect above just wrote the same review back). Restoring on top would
        // overwrite fresher edits with the snapshot taken before them.
        if (model.state is AiDebugLabState.LedgerReviewing ||
            model.state is AiDebugLabState.LedgerConfirming ||
            model.state is AiDebugLabState.LedgerExtracting
        ) {
            return@LaunchedEffect
        }
        val businessId = saved.businessId
        val sessionId = saved.sessionId
        if (businessId.isBlank() || sessionId.isBlank()) {
            savedLedgerReview = null
            return@LaunchedEffect
        }
        val balances = if (ledgerBalancesResolver != null) {
            withContext(Dispatchers.IO) {
                runCatching { ledgerBalancesResolver(businessId) }.getOrDefault(emptyMap())
            }
        } else {
            emptyMap()
        }
        val customerCandidates = if (ledgerCustomersResolver != null) {
            withContext(Dispatchers.IO) {
                runCatching { ledgerCustomersResolver(businessId) }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        val productCandidates = if (ledgerProductsResolver != null) {
            withContext(Dispatchers.IO) {
                runCatching { ledgerProductsResolver(businessId) }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        val registeredOperations = loadRegisteredAround(
            resolver = ledgerRegisteredResolver,
            businessId = businessId,
            operations = saved.operations,
            pageDateEpochDay = saved.pageDateEpochDay,
        )
        ledgerReviewContext = LedgerReviewContext(
            existingCustomerBalances = balances,
            customerCandidates = customerCandidates,
            productCandidates = productCandidates,
            registeredOperations = registeredOperations,
        )
        model = AiDebugLabMachine.onRestoreLedgerReview(
            model = model,
            sessionId = sessionId,
            businessId = businessId,
            saved = saved,
            existingCustomerBalances = balances,
            customerCandidates = customerCandidates,
            productCandidates = productCandidates,
            registeredOperations = registeredOperations,
        )
    }

    // Sweeps captures orphaned by an earlier dialog when this one opens, not in onDispose:
    // leaving composition can mean the Activity was destroyed while the camera app was still
    // writing to that very file, and deleting it there threw away the photo the user just took.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { cameraStore.deleteStaleExcept(listOf(pendingLedgerUri)) }
    }

    /**
     * Stage 2 of the import metric: the import ended, as registered or as abandoned.
     *
     * Launched on the GlobalScope on purpose: this write must survive the screen that produced
     * it. Launching it from the Compose scope and closing the lab right after CANCELS the write
     * — the exact failure the two-stage design exists to prevent. The recorder is idempotent and
     * wrapped in runCatching, so a failure costs the metric, never the import.
     */
    val recordImportEnded: (Boolean, AiDebugLabState.LedgerReviewing) -> Unit = { registered, reviewing ->
        val metricDao = ledgerImportMetricDao
        if (metricDao != null) {
            val params = reviewing.toImportEndParams(registered)
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                com.cuentivo.app.domain.LedgerImportMetricRecorder.onImportEnded(
                    dao = metricDao,
                    sessionId = params.sessionId,
                    registered = params.registered,
                    originalOperations = params.originalOperations,
                    finalReview = params.finalReview,
                    duplicateSuggestedCount = params.duplicateSuggestedCount,
                    parserExcludedCount = params.parserExcludedCount,
                    nowEpochMillis = params.nowEpochMillis,
                )
            }
        }
    }

    // One close action: cancel the in-flight request (generation bump) BEFORE closing, so a late
    // transport result is always invalidated. Also sweeps all capture temp files and releases recognizer.
    val closeLab = {
        // Closing the lab with a review open ends that import as abandoned: the person walked
        // away from the page. The metric is written before the state is torn down.
        val reviewingAtClose = model.state as? AiDebugLabState.LedgerReviewing
        if (reviewingAtClose != null) {
            recordImportEnded(false, reviewingAtClose)
        }
        effectiveVoiceRecognizer.destroy()
        voiceModel = VoiceCaptureMachine.onCancel(voiceModel)
        model = AiDebugLabMachine.onCancel(model)
        captureStore.cleanupAll()
        cameraStore.deleteStale(except = null)
        // Closing the lab ends any import that was under way, review included: a page abandoned
        // this way must not resurrect on the next recreation.
        savedLedgerReview = null
        onClose()
    }

    val cancelVoiceCapture = {
        effectiveVoiceRecognizer.cancel()
        voiceModel = VoiceCaptureMachine.onCancel(voiceModel)
    }

    val startVoiceListening: (Long) -> Unit = { generation ->
        effectiveVoiceRecognizer.startListening(
            onPartialResult = { partial ->
                voiceModel = VoiceCaptureMachine.onPartialResult(voiceModel, partial, generation)
            },
            onFinalResult = { text ->
                val nextModel = VoiceCaptureMachine.onFinalResult(voiceModel, text, generation)
                voiceModel = nextModel
                if (nextModel.state is VoiceCaptureState.Transcribed) {
                    val transcribedText = nextModel.state.text
                    // The recognized text enters the editable field without auto-submitting
                    model = AiDebugLabMachine.onTextChanged(model, transcribedText)
                    voiceModel = VoiceCaptureMachine.onCancel(nextModel)
                }
            },
            onError = { reason ->
                voiceModel = VoiceCaptureMachine.onError(voiceModel, reason, generation)
            },
            onRmsChanged = { rms ->
                val level = normalizeRmsDb(rms)
                voiceModel = VoiceCaptureMachine.onAmplitude(voiceModel, level, generation)
            },
            onEndOfSpeech = {
                voiceModel = VoiceCaptureMachine.onEndOfSpeech(voiceModel, generation)
            },
            onListeningStarted = {
                voiceModel = VoiceCaptureMachine.onListeningResumed(voiceModel, generation)
            },
        )
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val nextModel = if (granted) {
            VoiceCaptureMachine.onPermissionGranted(
                voiceModel,
                isRecognizerAvailable = effectiveVoiceRecognizer.isAvailable(),
            )
        } else {
            VoiceCaptureMachine.onPermissionDenied(voiceModel)
        }
        voiceModel = nextModel
        if (nextModel.state is VoiceCaptureState.Listening) {
            startVoiceListening(nextModel.generation)
        }
    }

    val onMicTapped = {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val isAvailable = effectiveVoiceRecognizer.isAvailable()
        val nextModel = VoiceCaptureMachine.onMicTapped(voiceModel, hasPermission, isAvailable)
        voiceModel = nextModel

        when (nextModel.state) {
            VoiceCaptureState.PermissionRequired -> {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            is VoiceCaptureState.Listening -> {
                startVoiceListening(nextModel.generation)
            }
            else -> {
                // Unavailable state handled in UI
            }
        }
    }

    // Importing a page of a paper notebook: its own launchers, so a photo taken for the chat can
    // never be mistaken for a page under review and the other way round.
    val discardLedgerCapture: () -> Unit = {
        ledgerCaptureFile?.delete()
        ledgerCaptureFilePath = null
        ledgerSessionId = null
        ledgerBusinessId = null
        // The import is over: the saved review must not outlive it, or a cancelled page would
        // resurrect on the next recreation.
        savedLedgerReview = null
    }

    val runLedgerExtraction: (java.io.File, String) -> Unit = { file, mimeType ->
        val call = extractLedgerPage
        val businessId = ledgerBusinessId
        val sessionId = ledgerSessionId
        if (call == null || businessId.isNullOrBlank() || sessionId == null) {
            file.delete()
            discardLedgerCapture()
        } else {
            ledgerCaptureFilePath = file.absolutePath
            model = AiDebugLabMachine.onStartLedgerExtraction(model, sessionId, businessId)
            val token = model.generation
            scope.launch {
                val balances = if (ledgerBalancesResolver != null) {
                    withContext(Dispatchers.IO) {
                        runCatching { ledgerBalancesResolver(businessId) }.getOrDefault(emptyMap())
                    }
                } else {
                    emptyMap()
                }
                val customerCandidates = if (ledgerCustomersResolver != null) {
                    withContext(Dispatchers.IO) {
                        runCatching { ledgerCustomersResolver(businessId) }.getOrDefault(emptyList())
                    }
                } else {
                    emptyList()
                }
                val productCandidates = if (ledgerProductsResolver != null) {
                    withContext(Dispatchers.IO) {
                        runCatching { ledgerProductsResolver(businessId) }.getOrDefault(emptyList())
                    }
                } else {
                    emptyList()
                }
                // One photo, one paid call: the clock around it is the cost metric of this import.
                val startedAt = System.currentTimeMillis()
                val outcome = withContext(Dispatchers.IO) {
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    if (bytes == null) {
                        null
                    } else {
                        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        call(java.util.UUID.randomUUID().toString(), encoded, mimeType)
                    }
                }
                val callDurationMillis = System.currentTimeMillis() - startedAt
                // The failure path checks the token too. Without it a slow extraction that failed
                // could cancel the review of a later photo and delete its file underneath it.
                if (token == model.generation) {
                    when (outcome) {
                        is LedgerExtractionCallResult.Extracted -> {
                            // Stage 1 of the metric: the page was read, the review is about to
                            // open. Written now — not at the end — so an import that dies halfway
                            // still left its trace, with the counts that are already final.
                            val metricDao = ledgerImportMetricDao
                            if (metricDao != null) {
                                val detectedOperationsCount = outcome.extraction.operations.size
                                val discardsCount = outcome.extraction.discards.size
                                withContext(Dispatchers.IO) {
                                    com.cuentivo.app.domain.LedgerImportMetricRecorder.onExtractionArrived(
                                        dao = metricDao,
                                        sessionId = sessionId,
                                        detectedCount = detectedOperationsCount,
                                        unreadableCount = outcome.extraction.operations.count { it.unreadableFields.isNotEmpty() },
                                        duplicateSuggestedCount = 0,
                                        parserExcludedCount = discardsCount,
                                        geminiCalls = 1,
                                        geminiDurationMillis = callDurationMillis,
                                        contractVersion = outcome.extraction.contractVersion,
                                        nowEpochMillis = System.currentTimeMillis(),
                                    )
                                }
                            }
                            model = AiDebugLabMachine.onLedgerExtractionResult(
                                model = model,
                                sessionId = sessionId,
                                businessId = businessId,
                                operations = outcome.extraction.operations,
                                discards = outcome.extraction.discards,
                                unreadTextRemaining = outcome.extraction.unreadTextRemaining,
                                pageIncomplete = outcome.extraction.pageIncomplete,
                                pageType = outcome.extraction.pageType,
                                pageTotals = outcome.extraction.pageTotals,
                                existingCustomerBalances = balances,
                                customerCandidates = customerCandidates,
                                productCandidates = productCandidates,
                                // Asked only once the page is read, and around the dates the page
                                // itself carries. Querying beforehand meant guessing the range from
                                // today, so a notebook photographed months after it was written was
                                // compared against days none of its rows could fall in.
                                registeredOperations = loadRegisteredAround(
                                    resolver = ledgerRegisteredResolver,
                                    businessId = businessId,
                                    operations = outcome.extraction.operations,
                                ),
                                generation = token,
                                // The date the server read off the sheet, offered — never
                                // applied — by the page date card.
                                detectedPageDate = outcome.extraction.detectedPageDate,
                                // The cross-check against the Ledger de MexaLM, when the server
                                // sent one. It only says where two readings disagree; it never
                                // changes what this page registers.
                                mexalmCrosscheck = outcome.extraction.mexalmCrosscheck,
                            )
                        }
                        else -> {
                            // A page that could not be read leaves nothing behind: no half review,
                            // and no photo waiting on disk for an import that is not happening.
                            // But it does say why: silently returning to the chat is how this
                            // looked like the button did nothing at all.
                            discardLedgerCapture()
                            val reason = when (outcome) {
                                is LedgerExtractionCallResult.QuotaExhausted -> AiChatMessage.AssistantQuotaExhausted
                                is LedgerExtractionCallResult.RateLimited -> AiChatMessage.AssistantRateLimited
                                is LedgerExtractionCallResult.Disabled -> AiChatMessage.AssistantDisabled
                                is LedgerExtractionCallResult.EntitlementRequired -> AiChatMessage.AssistantEntitlementRequired
                                else -> AiChatMessage.AssistantLedgerFailed
                            }
                            model = AiDebugLabMachine.onCancelLedger(model).let { cancelled ->
                                cancelled.copy(messages = cancelled.messages + reason)
                            }
                        }
                    }
                }
            }
        }
    }

    val ledgerCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingLedgerUri ?: return@rememberLauncherForActivityResult
        pendingLedgerUri = null
        if (!success) {
            cameraStore.delete(uri)
            discardLedgerCapture()
            return@rememberLauncherForActivityResult
        }
        val file = cameraStore.fileFor(uri)
        scope.launch {
            // A camera photo at full resolution does not fit, and refusing it would make the main
            // way of using this feature useless. It is scaled and rotated instead.
            val mimeType = withContext(Dispatchers.IO) { LedgerImagePreparer.prepareInPlace(file) }
            if (mimeType == null) {
                cameraStore.delete(uri)
                discardLedgerCapture()
                model = model.copy(messages = model.messages + AiChatMessage.AssistantLedgerFailed)
            } else {
                runLedgerExtraction(file, mimeType)
            }
        }
    }

    val ledgerPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            discardLedgerCapture()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                val mimeType = context.contentResolver.getType(uri)
                if (!AiCapturePolicy.isAllowedMime(mimeType)) return@withContext null
                val target = captureStore.createCaptureFile(mimeType!!) ?: return@withContext null
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    val preparedMime = LedgerImagePreparer.prepareInPlace(target)
                    if (preparedMime == null) {
                        target.delete()
                        null
                    } else {
                        target to preparedMime
                    }
                }.getOrNull()
            }
            if (prepared == null) {
                // A copy that failed halfway still left a file behind. And say so: the camera path
                // already did, and a gallery pick that ends in silence is indistinguishable from
                // the button doing nothing.
                discardLedgerCapture()
                model = model.copy(messages = model.messages + AiChatMessage.AssistantLedgerFailed)
            } else {
                runLedgerExtraction(prepared.first, prepared.second)
            }
        }
    }

    BackHandler(onBack = closeLab)

    // Reviewing a photographed ledger page takes over the whole surface: it is a different task
    // from the chat, and mixing them would let a stray dictation edit a page under review.
    // Reading a page takes seconds against the network. Showing nothing at all is
    // indistinguishable from the feature doing nothing, which is exactly how it looked.
    val ledgerExtracting = model.state as? AiDebugLabState.LedgerExtracting
    if (ledgerExtracting != null) {
        Dialog(
            onDismissRequest = { },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.ai_ledger_extracting_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ai_ledger_extracting_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val ledgerReviewing = model.state as? AiDebugLabState.LedgerReviewing
    if (ledgerReviewing != null) {
        // v44: la consulta a Room se hizo una vez al extraer/restaurar, con la ventana de fechas que
        // había entonces. Si durante la revisión cambia la fecha de un renglón o la de la página, esa
        // ventana deja de cubrir lo que el detector compara (una página de 2023 sin fechas por renglón
        // consultaba 2026 y no encontraba nada). Se vuelve a consultar cuando cambia la firma de fechas
        // efectivas, con la MISMA definición de ventana que usa el detector (`LedgerEffectiveDate`).
        val effectiveDateSignature = ledgerReviewing.review.operations.joinToString("|") { op ->
            "${op.lineIndex}:${com.cuentivo.app.domain.LedgerEffectiveDate.of(op, ledgerReviewing.review.pageDateEpochDay, ledgerReviewing.review.todayEpochDay)}"
        } + "|page:${ledgerReviewing.review.pageDateEpochDay}"
        LaunchedEffect(effectiveDateSignature, ledgerReviewing.sessionId) {
            if (ledgerRegisteredResolver == null) return@LaunchedEffect
            val refreshed = loadRegisteredAround(
                resolver = ledgerRegisteredResolver,
                businessId = ledgerReviewing.businessId,
                operations = ledgerReviewing.review.operations,
                pageDateEpochDay = ledgerReviewing.review.pageDateEpochDay,
            )
            model = AiDebugLabMachine.onUpdateLedgerRegisteredOperations(
                model = model,
                sessionId = ledgerReviewing.sessionId,
                registeredOperations = refreshed,
            )
        }
        val deterministicObjections = com.cuentivo.app.domain.LedgerDeterministicObjections.evaluate(
            operations = ledgerReviewing.review.operations,
            existingCustomerCandidates = ledgerReviewing.customerCandidates,
            catalogProducts = ledgerReviewing.productCandidates,
            pageTotals = ledgerReviewing.review.pageTotals,
            pageDateEpochDay = ledgerReviewing.review.pageDateEpochDay,
            todayEpochDay = ledgerReviewing.review.todayEpochDay,
        )
        val cancelLedger = {
            // Stage 2 of the metric: the person discarded this page, so the import ended
            // abandoned. Written before the review is torn down, with it still in hand.
            recordImportEnded(false, ledgerReviewing)
            discardLedgerCapture()
            model = AiDebugLabMachine.onCancelLedger(model)
        }
        Dialog(
            onDismissRequest = cancelLedger,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LedgerReviewContent(
                review = ledgerReviewing.review,
                captureFile = ledgerCaptureFile,
                customerMatches = ledgerReviewing.customerMatches,
                productMatches = ledgerReviewing.productMatches,
                duplicateMatches = ledgerReviewing.duplicateMatches,
                objections = deterministicObjections,
                isPageCompletenessConfirmed = ledgerReviewing.isPageCompletenessConfirmed,
                onConfirmPageCompleteness = {
                    model = AiDebugLabMachine.onConfirmLedgerPageCompleteness(model, ledgerReviewing.sessionId, ledgerReviewing.businessId)
                },
                onAcceptProductAnchor = { lineIndex, candidate ->
                    // Only the name. The plan still writes an uncatalogued line, so nothing here
                    // moves stock or attaches a cost, and nothing here touches the amount.
                    model = AiDebugLabMachine.onUpdateLedgerOperations(model, ledgerReviewing.sessionId) { operations ->
                        LedgerReviewEngine.setOperationDescription(operations, lineIndex, candidate.name)
                    }
                },
                onAcceptCustomerAnchor = { lineIndex, candidate ->
                    // Only from an explicit tap. A score, however high, never calls this: showing a
                    // candidate is not the same as somebody confirming it, and a wrong anchor on a
                    // payment settles another person's real debt.
                    model = AiDebugLabMachine.onUpdateLedgerOperations(model, ledgerReviewing.sessionId) { operations ->
                        LedgerReviewEngine.setOperationCustomerAnchor(
                            currentOperations = operations,
                            lineIndex = lineIndex,
                            customerId = candidate.id,
                            customerName = candidate.name,
                        )
                    }
                },
                onUpdateAmount = { lineIndex, amountCents ->
                    model = AiDebugLabMachine.onUpdateLedgerOperations(model, ledgerReviewing.sessionId) { operations ->
                        LedgerReviewEdits.applyAmount(operations, lineIndex, amountCents)
                    }
                },
                onUpdateCustomer = { lineIndex, customerName ->
                    model = AiDebugLabMachine.onUpdateLedgerOperations(model, ledgerReviewing.sessionId) { operations ->
                        LedgerReviewEdits.applyCustomer(operations, lineIndex, customerName)
                    }
                },
                onUpdateKind = { lineIndex, kind ->
                    model = AiDebugLabMachine.onUpdateLedgerOperations(model, ledgerReviewing.sessionId) { operations ->
                        LedgerReviewEdits.applyKind(operations, lineIndex, kind)
                    }
                },
                onUpdateDate = { lineIndex, date ->
                    model = AiDebugLabMachine.onUpdateLedgerOperations(model, ledgerReviewing.sessionId) { operations ->
                        LedgerReviewEdits.applyDate(
                            operations,
                            lineIndex,
                            date,
                            java.time.LocalDate.now().toEpochDay(),
                        )
                    }
                },
                onUpdatePageDate = { pageDateEpochDay ->
                    model = AiDebugLabMachine.onUpdateLedgerPageDate(
                        model = model,
                        sessionId = ledgerReviewing.sessionId,
                        pageDateEpochDay = pageDateEpochDay,
                    )
                },
                dueDateDays = ledgerReviewing.dueDateDays,
                onUpdateDueDateDays = { dueDateDays ->
                    model = AiDebugLabMachine.onUpdateLedgerDueDateDays(
                        model = model,
                        sessionId = ledgerReviewing.sessionId,
                        dueDateDays = dueDateDays,
                    )
                },
                onRemoveRow = { lineIndex ->
                    model = AiDebugLabMachine.onUpdateLedgerOperations(model, ledgerReviewing.sessionId) { operations ->
                        LedgerReviewEdits.removeRow(operations, lineIndex)
                    }
                },
                onConfirmObjection = { lineIndex ->
                    model = AiDebugLabMachine.onUpdateLedgerOperations(model, ledgerReviewing.sessionId) { operations ->
                        LedgerReviewEdits.confirmObjection(operations, lineIndex)
                    }
                },
                onConfirm = {
                    val write = confirmLedgerBatch
                    val outcome = AiDebugLabMachine.onConfirmLedger(model, ledgerReviewing.sessionId, ledgerReviewing.businessId)
                    if (outcome is AiLedgerConfirmOutcome.Started && write != null) {
                        model = outcome.model
                        val token = model.generation
                        val sessionId = ledgerReviewing.sessionId
                        val businessId = ledgerReviewing.businessId
                        val operations = ledgerReviewing.review.operations
                        val pageDateEpochDay = ledgerReviewing.review.pageDateEpochDay
                        // Capturado en el momento de pulsar, y leído del estado que devolvió la
                        // máquina: así la prueba de `onConfirmLedger` cubre el valor que de verdad
                        // se escribe, no una copia paralela tomada de la clausura.
                        val dueDateDays = (outcome.model.state as AiDebugLabState.LedgerConfirming).dueDateDays
                        // El posible duplicado frena el renglón igual que una objeción determinista
                        // (v44): hasta que la persona confirme "Sí, es otra venta", no se escribe.
                        // Se calcula con el mismo objeto puro que usa la pantalla, para que lo que
                        // se ve bloqueado y lo que se escribe bloqueado no puedan discrepar.
                        val blockedLineIndices = com.cuentivo.app.domain.LedgerDuplicateBlocking.blockedLineIndices(
                            operations = operations,
                            duplicateMatches = ledgerReviewing.duplicateMatches,
                            deterministicBlockedLineIndices = deterministicObjections.mapTo(LinkedHashSet()) { it.lineIndex },
                        )
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { write(businessId, sessionId, operations, pageDateEpochDay, dueDateDays, blockedLineIndices, com.cuentivo.app.domain.LedgerPageCompleteness.modelSaysTextMayBeMissing(ledgerReviewing.review), ledgerReviewing.isPageCompletenessConfirmed) }.getOrNull()
                            }
                            model = AiDebugLabMachine.onLedgerBatchResult(
                                model = model,
                                sessionId = sessionId,
                                businessId = businessId,
                                generation = token,
                                result = result,
                            )
                            // The photo is released only once the page is written. Releasing it on
                            // the way in would leave a failed import without the evidence the
                            // person needs to correct it.
                            if (result is com.cuentivo.app.domain.LedgerBatchConfirmation.Recorded || result is com.cuentivo.app.domain.LedgerBatchConfirmation.AlreadyRecorded) {
                                // Stage 2 of the metric: this import ended registered. Written
                                // before the capture is discarded, with the review still in hand.
                                recordImportEnded(true, ledgerReviewing)
                                discardLedgerCapture()
                            }
                        }
                    }
                },
                isRegistrationBlockedByLegacySave = ledgerReviewing.isLegacySaveWithoutSessionKey,
                onCancel = cancelLedger,
            )
        }
        return
    }
    Dialog(
        onDismissRequest = closeLab,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ai_debug_lab_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = closeLab) { Text(stringResource(R.string.ai_debug_lab_close)) }
            }
            // The notice is informational, not a failure: it used to sit here permanently in the
            // error colour, so the first thing a paying user saw on entering was a red alert.
            // It now fades in, states its point, and leaves. What actually protects the user —
            // that nothing is recorded without confirming — is said again at confirmation time,
            // which is when it can be acted on.
            var noticeVisible by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(NOTICE_VISIBLE_MILLIS)
                noticeVisible = false
            }
            AnimatedVisibility(
                visible = noticeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = stringResource(R.string.ai_debug_lab_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Temporary operational chat transcript: user bubbles and neutral assistant cards.
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(model.messages) { message ->
                    val isCurrentPaymentPreview = message is AiChatMessage.AssistantPaymentPreview && (
                        (model.state as? AiDebugLabState.PaymentConfirmation)?.preview == message.preview ||
                            (model.state as? AiDebugLabState.PaymentConfirming)?.preview == message.preview
                    )
                    val isCurrentSalePreview = message is AiChatMessage.AssistantSalePreview && (
                        (model.state as? AiDebugLabState.SaleConfirmation)?.preview == message.preview ||
                            (model.state as? AiDebugLabState.SaleConfirming)?.preview == message.preview
                    )
                    val isCurrentSaleCustomerSelection = message is AiChatMessage.AssistantSaleCustomerSelection &&
                        (model.state as? AiDebugLabState.SaleResolution)?.result is com.cuentivo.app.ai.AiSalePreviewResult.CustomerSelectionRequired
                    val isCurrentSaleProductSelection = message is AiChatMessage.AssistantSaleProductSelection &&
                        (model.state as? AiDebugLabState.SaleResolution)?.result is com.cuentivo.app.ai.AiSalePreviewResult.ProductSelectionRequired
                    val isCurrentQuerySelection = message is AiChatMessage.AssistantQueryAnswer &&
                        message.result is com.cuentivo.app.ai.AiBusinessQueryResult.CustomerSelectionRequired &&
                        (model.state as? AiDebugLabState.QueryAnswer)?.result is com.cuentivo.app.ai.AiBusinessQueryResult.CustomerSelectionRequired
                    val isConfirming = model.state is AiDebugLabState.PaymentConfirming || model.state is AiDebugLabState.SaleConfirming
                    ChatMessageRow(
                        message = message,
                        onConfirmPayment = if (isCurrentPaymentPreview && isHydrated && confirmPayment != null && activeBusinessId?.isNotBlank() == true) {
                            {
                                val outcome = AiDebugLabMachine.onConfirmPayment(model, activeBusinessId)
                                if (outcome is AiPaymentConfirmOutcome.Started) {
                                    model = outcome.model
                                    val token = outcome.generation
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            try {
                                                confirmPayment(outcome.request)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                AiPaymentExecutionResult.Unavailable
                                            }
                                        }
                                        model = AiDebugLabMachine.onPaymentExecutionResult(model, result, token)
                                    }
                                }
                            }
                        } else null,
                        onCancelPayment = if (isCurrentPaymentPreview && isHydrated) {
                            { model = AiDebugLabMachine.onCancel(model) }
                        } else null,
                        onConfirmSale = if (isCurrentSalePreview && isHydrated && confirmSale != null && activeBusinessId?.isNotBlank() == true) {
                            {
                                // El plazo elegido en el borrador se resuelve contra hoy al confirmar.
                                val outcome = AiDebugLabMachine.onConfirmSale(
                                    model,
                                    activeBusinessId,
                                    todayEpochDay = java.time.LocalDate.now().toEpochDay(),
                                )
                                if (outcome is AiSaleConfirmOutcome.Started) {
                                    model = outcome.model
                                    val token = outcome.generation
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            try {
                                                confirmSale(outcome.request)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                AiSaleExecutionResult.Unavailable
                                            }
                                        }
                                        model = AiDebugLabMachine.onSaleExecutionResult(model, result, token)
                                    }
                                }
                            }
                        } else null,
                        onSelectCustomerCandidate = if (isCurrentSaleCustomerSelection && isHydrated && salePreviewResolver != null && activeBusinessId?.isNotBlank() == true) {
                            { choice ->
                                val outcome = AiDebugLabMachine.onChooseCustomer(model, choice)
                                if (outcome is AiSaleChoiceOutcome.Selected) {
                                    model = outcome.model
                                    val token = outcome.generation
                                    scope.launch {
                                        val resolutionResult = withContext(Dispatchers.IO) {
                                            try {
                                                salePreviewResolver(activeBusinessId, outcome.proposal, outcome.choices)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                com.cuentivo.app.ai.AiSalePreviewResult.Unavailable
                                            }
                                        }
                                        model = AiDebugLabMachine.onSalePreviewResult(model, resolutionResult, token)
                                    }
                                }
                            }
                        } else null,
                        onSelectProductCandidate = if (isCurrentSaleProductSelection && isHydrated && salePreviewResolver != null && activeBusinessId?.isNotBlank() == true) {
                            { productName, choice ->
                                val outcome = AiDebugLabMachine.onChooseProduct(model, productName, choice)
                                if (outcome is AiSaleChoiceOutcome.Selected) {
                                    model = outcome.model
                                    val token = outcome.generation
                                    scope.launch {
                                        val resolutionResult = withContext(Dispatchers.IO) {
                                            try {
                                                salePreviewResolver(activeBusinessId, outcome.proposal, outcome.choices)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                com.cuentivo.app.ai.AiSalePreviewResult.Unavailable
                                            }
                                        }
                                        model = AiDebugLabMachine.onSalePreviewResult(model, resolutionResult, token)
                                    }
                                }
                            }
                        } else null,
                        onSelectQueryCustomerCandidate = if (isCurrentQuerySelection && isHydrated && businessQueryResolver != null && activeBusinessId?.isNotBlank() == true) {
                            { customerId ->
                                val outcome = AiDebugLabMachine.onChooseQueryCustomer(model, customerId)
                                if (outcome is com.cuentivo.app.ui.ai.AiQueryChoiceOutcome.Selected) {
                                    model = outcome.model
                                    val token = outcome.generation
                                    scope.launch {
                                        val queryResult = withContext(Dispatchers.IO) {
                                            try {
                                                businessQueryResolver(activeBusinessId, outcome.proposal, outcome.choice)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                com.cuentivo.app.ai.AiBusinessQueryResult.Unavailable
                                            }
                                        }
                                        model = AiDebugLabMachine.onQueryResult(model, queryResult, token)
                                    }
                                }
                            }
                        } else null,
                        onCancelSale = if ((isCurrentSalePreview || isCurrentSaleCustomerSelection || isCurrentSaleProductSelection) && isHydrated) {
                            { model = AiDebugLabMachine.onCancel(model) }
                        } else null,
                        onOpenPaywall = onOpenPaywall,
                        saleDueDateDays = (model.state as? AiDebugLabState.SaleConfirmation)?.dueDateDays,
                        onUpdateSaleDueDateDays = if (isCurrentSalePreview && isHydrated) {
                            { days -> model = AiDebugLabMachine.onUpdateSaleDueDateDays(model, days) }
                        } else null,
                        isConfirming = isConfirming,
                    )
                }
            }
            if (cameraUnavailable) {
                Text(
                    text = stringResource(R.string.ai_debug_chat_camera_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (voiceModel.state is VoiceCaptureState.Listening) {
                val listeningState = voiceModel.state as VoiceCaptureState.Listening
                val partial = listeningState.partialText
                val level = listeningState.level
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VoiceWaveformBars(
                            level = level,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = if (partial.isNotBlank()) partial else stringResource(R.string.ai_voice_listening),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = cancelVoiceCapture) {
                            Text(stringResource(R.string.ai_voice_cancel))
                        }
                    }
                }
            } else if (voiceModel.state is VoiceCaptureState.Processing) {
                val processingState = voiceModel.state as VoiceCaptureState.Processing
                val partial = processingState.partialText
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (partial.isNotBlank()) partial else stringResource(R.string.ai_voice_processing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = cancelVoiceCapture) {
                            Text(stringResource(R.string.ai_voice_cancel))
                        }
                    }
                }
            } else if (voiceModel.state is VoiceCaptureState.Unavailable) {
                val reason = (voiceModel.state as VoiceCaptureState.Unavailable).reason
                val message = when (reason) {
                    VoiceUnavailableReason.NO_RECOGNIZER -> stringResource(R.string.ai_voice_unavailable_no_recognizer)
                    VoiceUnavailableReason.PERMISSION_DENIED -> stringResource(R.string.ai_voice_unavailable_permission_denied)
                    VoiceUnavailableReason.NO_SPEECH -> stringResource(R.string.ai_voice_unavailable_no_speech)
                    VoiceUnavailableReason.RECOGNIZER_ERROR -> stringResource(R.string.ai_voice_unavailable_recognizer_error)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = cancelVoiceCapture) {
                        Text(stringResource(R.string.ai_debug_lab_close))
                    }
                }
            }
            // Bottom-aligned composer: text send uses the turn policy.
            val isVoiceActive = voiceModel.state is VoiceCaptureState.Listening || voiceModel.state is VoiceCaptureState.Processing
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = model.text,
                    onValueChange = { model = AiDebugLabMachine.onTextChanged(model, it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.ai_debug_lab_input_hint)) },
                    enabled = isHydrated && model.state != AiDebugLabState.Sending && !isVoiceActive,
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    // isVoiceDisabled, not just Sending: dictating while a photographed page is being
                    // extracted, reviewed or written is the race the plan closes by turning voice
                    // off, and hiding the button is not the same as refusing the input.
                    enabled = isHydrated && !model.isVoiceDisabled,
                    onClick = {
                        if (isVoiceActive) {
                            cancelVoiceCapture()
                        } else {
                            onMicTapped()
                        }
                    },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (isVoiceActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = stringResource(R.string.ai_voice_button),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = isHydrated && model.canSubmit && !isVoiceActive,
                    onClick = {
                        val trimmedText = model.text.trim()
                        val request = AiInterpretRequest(
                            text = trimmedText,
                            requestId = UUID.randomUUID().toString(),
                        )
                        val outcome = AiDebugLabMachine.onSubmit(model)
                        if (outcome is AiDebugLabSubmitOutcome.Submitted) {
                            model = outcome.model
                            val token = outcome.generation
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { repository.appendUserText(trimmedText) }
                                }
                                val turnResult = AiDebugLabTurnPolicy.run(transport, request)
                                when (turnResult) {
                                    AiDebugLabTurnResult.OutOfScope -> {
                                        withContext(Dispatchers.IO) {
                                            runCatching { repository.appendAssistant(AiDailyMessageKind.ASSISTANT_OUT_OF_SCOPE) }
                                        }
                                        model = AiDebugLabMachine.onOutOfScopeResult(model, token)
                                    }
                                    is AiDebugLabTurnResult.Remote -> {
                                        val result = turnResult.result
                                        when (result) {
                                            CallableAiProposalResult.Unavailable -> {
                                                withContext(Dispatchers.IO) {
                                                    runCatching { repository.appendAssistant(AiDailyMessageKind.ASSISTANT_UNAVAILABLE) }
                                                }
                                            }
                                            CallableAiProposalResult.QuotaExhausted -> {
                                                withContext(Dispatchers.IO) {
                                                    runCatching { repository.appendAssistant(AiDailyMessageKind.ASSISTANT_QUOTA_EXHAUSTED) }
                                                }
                                            }
                                            CallableAiProposalResult.RateLimited -> {
                                                withContext(Dispatchers.IO) {
                                                    runCatching { repository.appendAssistant(AiDailyMessageKind.ASSISTANT_RATE_LIMITED) }
                                                }
                                            }
                                            CallableAiProposalResult.Disabled -> {
                                                withContext(Dispatchers.IO) {
                                                    runCatching { repository.appendAssistant(AiDailyMessageKind.ASSISTANT_DISABLED) }
                                                }
                                            }
                                            CallableAiProposalResult.EntitlementRequired -> {
                                                withContext(Dispatchers.IO) {
                                                    runCatching { repository.appendAssistant(AiDailyMessageKind.ASSISTANT_ENTITLEMENT_REQUIRED) }
                                                }
                                            }
                                            is CallableAiProposalResult.Proposal -> {}
                                        }
                                        val nextModel = AiDebugLabMachine.onResult(model, result, token)
                                        model = nextModel
                                        if (result is CallableAiProposalResult.Proposal &&
                                            result.proposal is RecordPaymentProposal &&
                                            nextModel.state is AiDebugLabState.ProposalPreview
                                        ) {
                                            val paymentProposal = result.proposal
                                            val resolutionResult = if (paymentPreviewResolver != null && activeBusinessId?.isNotBlank() == true) {
                                                withContext(Dispatchers.IO) {
                                                    try {
                                                        paymentPreviewResolver(activeBusinessId, paymentProposal)
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (_: Exception) {
                                                        AiPaymentPreviewResult.Unavailable
                                                    }
                                                }
                                            } else {
                                                AiPaymentPreviewResult.Unavailable
                                            }
                                            model = AiDebugLabMachine.onPaymentPreviewResult(model, resolutionResult, token)
                                        } else if (result is CallableAiProposalResult.Proposal &&
                                            result.proposal is CreateSaleProposal &&
                                            nextModel.state is AiDebugLabState.ProposalPreview
                                        ) {
                                            val saleProposal = result.proposal
                                            val resolutionResult = if (salePreviewResolver != null && activeBusinessId?.isNotBlank() == true) {
                                                withContext(Dispatchers.IO) {
                                                    try {
                                                        salePreviewResolver(activeBusinessId, saleProposal, com.cuentivo.app.ai.AiSaleResolutionChoices())
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (_: Exception) {
                                                        AiSalePreviewResult.Unavailable
                                                    }
                                                }
                                            } else {
                                                AiSalePreviewResult.Unavailable
                                            }
                                            model = AiDebugLabMachine.onSalePreviewResult(model, resolutionResult, token)
                                        } else if (result is CallableAiProposalResult.Proposal &&
                                            result.proposal is BusinessQueryProposal &&
                                            nextModel.state is AiDebugLabState.ProposalPreview
                                        ) {
                                            val queryProposal = result.proposal
                                            val queryResolutionResult = if (businessQueryResolver != null && activeBusinessId?.isNotBlank() == true) {
                                                withContext(Dispatchers.IO) {
                                                    try {
                                                        businessQueryResolver(activeBusinessId, queryProposal, null)
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (_: Exception) {
                                                        com.cuentivo.app.ai.AiBusinessQueryResult.Unavailable
                                                    }
                                                }
                                            } else {
                                                com.cuentivo.app.ai.AiBusinessQueryResult.Unavailable
                                            }
                                            model = AiDebugLabMachine.onQueryResult(model, queryResolutionResult, token)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.ai_debug_lab_send),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // One way in, and it does not depend on the OCR engine. It used to sit inside
                // "if (ocrEngine != null)", and debugAiOcrEngine returns null in the release
                // variant, so the whole ledger import was invisible in the published build. Its
                // condition is its own: the callable and a business to write to.
                if (extractLedgerPage != null && activeBusinessId?.isNotBlank() == true) {
                    TextButton(
                        enabled = isHydrated && !model.isVoiceDisabled,
                        modifier = Modifier.testTag("ai-ledger-import-entry"),
                        onClick = {
                            // The import session is named before the camera opens, so a result
                            // that arrives late, or from a photo taken for a different page,
                            // cannot land on the review the person is looking at.
                            ledgerSessionId = java.util.UUID.randomUUID().toString()
                            ledgerBusinessId = activeBusinessId
                            showLedgerSourceSelector = true
                        },
                    ) {
                        Text(stringResource(R.string.ai_ledger_import_entry))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = isHydrated && model.state != AiDebugLabState.Sending,
                    onClick = { showClearDialog = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.ai_daily_chat_clear_today),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (showLedgerSourceSelector) {
            ModalBottomSheet(onDismissRequest = {
                showLedgerSourceSelector = false
                discardLedgerCapture()
            }) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    TextButton(
                        onClick = {
                            showLedgerSourceSelector = false
                            cameraUnavailable = false
                            cameraStore.deleteStaleExcept(listOf(pendingLedgerUri))
                            val attempt = runCatching {
                                val uri = cameraStore.createCaptureUri()
                                pendingLedgerUri = uri
                                ledgerCameraLauncher.launch(uri)
                            }
                            if (attempt.isFailure) {
                                pendingLedgerUri?.let(cameraStore::delete)
                                pendingLedgerUri = null
                                discardLedgerCapture()
                                cameraUnavailable = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ai_debug_chat_camera)) }
                    TextButton(
                        onClick = {
                            showLedgerSourceSelector = false
                            ledgerPickLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("ai-ledger-source-gallery"),
                    ) { Text(stringResource(R.string.ai_debug_chat_gallery)) }
                    TextButton(
                        onClick = {
                            showLedgerSourceSelector = false
                            discardLedgerCapture()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ai_debug_lab_close)) }
                }
            }
        }
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(stringResource(R.string.ai_daily_chat_clear_dialog_title)) },
                text = { Text(stringResource(R.string.ai_daily_chat_clear_dialog_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            cameraStore.deleteStale(except = null)
                            scope.launch {
                                val cleared = withContext(Dispatchers.IO) {
                                    runCatching { repository.clearToday() }.getOrNull()
                                }
                                if (cleared != null) {
                                    model = AiDebugLabMachine.onHistoryLoaded(model, cleared.messages)
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.ai_daily_chat_clear_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(stringResource(R.string.ai_daily_chat_clear_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatMessageRow(
    message: AiChatMessage,
    onConfirmPayment: (() -> Unit)? = null,
    onCancelPayment: (() -> Unit)? = null,
    onConfirmSale: (() -> Unit)? = null,
    onCancelSale: (() -> Unit)? = null,
    onSelectCustomerCandidate: ((com.cuentivo.app.ai.AiEntityChoice) -> Unit)? = null,
    onSelectProductCandidate: ((productName: String, choice: com.cuentivo.app.ai.AiEntityChoice) -> Unit)? = null,
    onSelectQueryCustomerCandidate: ((customerId: String) -> Unit)? = null,
    onOpenPaywall: (() -> Unit)? = null,
    /** El plazo del borrador de venta y su cambio. Null cuando no hay borrador vivo. */
    saleDueDateDays: Long? = null,
    onUpdateSaleDueDateDays: ((Long?) -> Unit)? = null,
    isConfirming: Boolean = false,
) {
    when (message) {
        is AiChatMessage.UserText -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = message.text,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(10.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        AiChatMessage.AssistantGreeting -> Text(stringResource(R.string.ai_daily_chat_greeting))
        is AiChatMessage.AssistantLedgerRecorded -> Text(
            stringResource(R.string.ai_ledger_recorded, message.operationsCount),
        )
        is AiChatMessage.AssistantLedgerAlreadyRecorded -> Text(stringResource(R.string.ai_ledger_recorded_already))
        AiChatMessage.AssistantLedgerFailed -> Text(stringResource(R.string.ai_ledger_failed))
        AiChatMessage.AssistantOutOfScope -> Text(stringResource(R.string.ai_daily_chat_out_of_scope))
        AiChatMessage.AssistantPending -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_debug_lab_thinking))
        }
        AiChatMessage.AssistantUnavailable -> Text(stringResource(R.string.ai_debug_lab_unavailable))
        AiChatMessage.AssistantQuotaExhausted -> Text(stringResource(R.string.ai_debug_lab_quota_exhausted))
        AiChatMessage.AssistantRateLimited -> Text(stringResource(R.string.ai_debug_lab_rate_limited))
        AiChatMessage.AssistantDisabled -> Text(stringResource(R.string.ai_debug_lab_disabled))
        AiChatMessage.AssistantEntitlementRequired -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.ai_debug_lab_entitlement_required),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onOpenPaywall != null) {
                Button(
                    onClick = onOpenPaywall,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.subscription_upgrade_ai_action))
                }
            }
        }
        is AiChatMessage.AssistantClarification -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val reasonText = when (message.reason) {
                com.cuentivo.app.ai.ClarificationReason.AMBIGUOUS_CUSTOMER -> stringResource(R.string.ai_clarification_reason_ambiguous_customer)
                com.cuentivo.app.ai.ClarificationReason.AMBIGUOUS_PRODUCT -> stringResource(R.string.ai_clarification_reason_ambiguous_product)
                com.cuentivo.app.ai.ClarificationReason.MISSING_CUSTOMER -> stringResource(R.string.ai_clarification_reason_missing_customer)
                com.cuentivo.app.ai.ClarificationReason.MISSING_PRODUCT -> stringResource(R.string.ai_clarification_reason_missing_product)
                com.cuentivo.app.ai.ClarificationReason.MISSING_AMOUNT -> stringResource(R.string.ai_clarification_reason_missing_amount)
                com.cuentivo.app.ai.ClarificationReason.UNKNOWN_INPUT -> stringResource(R.string.ai_clarification_reason_unknown_input)
            }
            Text(
                text = reasonText,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (message.slots.isNotEmpty()) {
                val customerSlotLabel = stringResource(R.string.ai_slot_customer)
                val productSlotLabel = stringResource(R.string.ai_slot_product)
                val amountSlotLabel = stringResource(R.string.ai_slot_amount)
                val quantitySlotLabel = stringResource(R.string.ai_slot_quantity)
                val periodSlotLabel = stringResource(R.string.ai_slot_period)
                val slotsFormatted = message.slots.joinToString { slot ->
                    when (slot) {
                        com.cuentivo.app.ai.UnresolvedSlot.CUSTOMER -> customerSlotLabel
                        com.cuentivo.app.ai.UnresolvedSlot.PRODUCT -> productSlotLabel
                        com.cuentivo.app.ai.UnresolvedSlot.AMOUNT -> amountSlotLabel
                        com.cuentivo.app.ai.UnresolvedSlot.QUANTITY -> quantitySlotLabel
                        com.cuentivo.app.ai.UnresolvedSlot.PERIOD -> periodSlotLabel
                    }
                }
                Text(
                    text = stringResource(R.string.ai_clarification_missing_slots_format, slotsFormatted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is AiChatMessage.AssistantProposal -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.ai_debug_chat_draft_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            ProposalPreviewContent(message.proposal)
        }
        is AiChatMessage.AssistantPaymentPreview -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.ai_payment_preview_draft_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message.preview.customerName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.ai_payment_preview_current_balance,
                    formatCents(message.preview.currentBalanceCents),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.ai_payment_preview_proposed_amount,
                    formatCents(message.preview.declaredAmountCents),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.ai_payment_preview_projected_balance,
                    formatCents(message.preview.projectedBalanceCents),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onConfirmPayment != null && onCancelPayment != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onConfirmPayment,
                        enabled = !isConfirming,
                    ) {
                        Text(stringResource(R.string.ai_payment_confirm_action))
                    }
                    TextButton(
                        onClick = onCancelPayment,
                        enabled = !isConfirming,
                    ) {
                        Text(stringResource(R.string.ai_payment_confirm_cancel))
                    }
                }
            }
        }
        AiChatMessage.AssistantPaymentCustomerNotFound -> Text(
            text = stringResource(R.string.ai_payment_preview_customer_not_found),
            style = MaterialTheme.typography.bodyMedium,
        )
        is AiChatMessage.AssistantPaymentCustomerSelection -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.ai_payment_preview_selection_required),
                style = MaterialTheme.typography.bodyMedium,
            )
            message.candidates.forEach { candidate ->
                Text(
                    text = "• " + candidate.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        is AiChatMessage.AssistantPaymentBlocked -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (message.reason) {
                AiPaymentBlockReason.NO_OUTSTANDING_DEBT -> {
                    val customerName = message.preview?.customerName
                    val text = if (!customerName.isNullOrBlank()) {
                        stringResource(R.string.ai_payment_preview_blocked_no_debt_with_name, customerName)
                    } else {
                        stringResource(R.string.ai_payment_preview_blocked_no_debt)
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                AiPaymentBlockReason.AMOUNT_EXCEEDS_DEBT -> {
                    val preview = message.preview
                    val text = if (preview != null) {
                        val debtFormatted = formatCents(preview.currentBalanceCents)
                        val paymentFormatted = formatCents(preview.declaredAmountCents)
                        stringResource(
                            R.string.ai_payment_preview_blocked_exceeds_debt,
                            preview.customerName,
                            debtFormatted,
                            paymentFormatted,
                        )
                    } else {
                        stringResource(R.string.ai_payment_preview_blocked_exceeds_debt_no_details)
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        is AiChatMessage.AssistantPaymentRecorded -> Text(
            text = stringResource(R.string.ai_payment_recorded_success),
            style = MaterialTheme.typography.bodyMedium,
        )
        AiChatMessage.AssistantPaymentStale -> Text(
            text = stringResource(R.string.ai_payment_stale_message),
            style = MaterialTheme.typography.bodyMedium,
        )
        AiChatMessage.AssistantPaymentRejected -> Text(
            text = stringResource(R.string.ai_payment_rejected_message),
            style = MaterialTheme.typography.bodyMedium,
        )
        is AiChatMessage.AssistantSalePreview -> AiSalePreviewContent(
            preview = message.preview,
            onConfirmSale = onConfirmSale,
            onCancelSale = onCancelSale,
            saleDueDateDays = saleDueDateDays,
            onUpdateSaleDueDateDays = onUpdateSaleDueDateDays,
            isConfirming = isConfirming,
        )
        is AiChatMessage.AssistantSaleCustomerSelection -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.ai_sale_preview_customer_selection_prompt),
                style = MaterialTheme.typography.bodyMedium,
            )
            message.candidates.forEach { candidate ->
                Button(
                    onClick = { onSelectCustomerCandidate?.invoke(com.cuentivo.app.ai.AiEntityChoice.UseExisting(candidate.customerId)) },
                    enabled = onSelectCustomerCandidate != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(candidate.displayName)
                }
            }
            OutlinedButton(
                onClick = { onSelectCustomerCandidate?.invoke(com.cuentivo.app.ai.AiEntityChoice.CreateNew) },
                enabled = onSelectCustomerCandidate != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ai_sale_choice_create_new))
            }
            if (onCancelSale != null) {
                TextButton(
                    onClick = onCancelSale,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.ai_sale_confirm_cancel))
                }
            }
        }
        is AiChatMessage.AssistantSaleProductSelection -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.ai_sale_preview_product_selection_prompt, message.requestedName),
                style = MaterialTheme.typography.bodyMedium,
            )
            message.candidates.forEach { candidate ->
                Button(
                    onClick = { onSelectProductCandidate?.invoke(message.requestedName, com.cuentivo.app.ai.AiEntityChoice.UseExisting(candidate.productId)) },
                    enabled = onSelectProductCandidate != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            R.string.ai_sale_preview_candidate_with_price,
                            candidate.displayName,
                            formatCents(candidate.salePriceCents),
                        ),
                    )
                }
            }
            OutlinedButton(
                onClick = { onSelectProductCandidate?.invoke(message.requestedName, com.cuentivo.app.ai.AiEntityChoice.CreateNew) },
                enabled = onSelectProductCandidate != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ai_sale_choice_create_new))
            }
            if (onCancelSale != null) {
                TextButton(
                    onClick = onCancelSale,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.ai_sale_confirm_cancel))
                }
            }
        }
        is AiChatMessage.AssistantSaleBlocked -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (message.reason) {
                AiSaleBlockReason.INITIAL_PAYMENT_EXCEEDS_TOTAL -> {
                    val preview = message.preview
                    val text = if (preview != null) {
                        val initialFormatted = formatCents(preview.initialPaymentCents)
                        val totalFormatted = formatCents(preview.totalCents)
                        stringResource(R.string.ai_sale_preview_blocked_overpayment, initialFormatted, totalFormatted)
                    } else {
                        stringResource(R.string.ai_sale_preview_blocked_overpayment_no_details)
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                AiSaleBlockReason.ARITHMETIC_OVERFLOW -> Text(
                    text = stringResource(R.string.ai_sale_preview_blocked_overflow),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AiSaleBlockReason.INVALID_LINE_QUANTITY -> Text(
                    text = stringResource(R.string.ai_sale_preview_blocked_invalid_quantity),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AiSaleBlockReason.INVALID_LINE_PRICE -> Text(
                    text = stringResource(R.string.ai_sale_preview_blocked_invalid_price),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AiSaleBlockReason.INCONSISTENT_NEW_PRODUCT_PRICE -> Text(
                    text = stringResource(R.string.ai_sale_preview_blocked_inconsistent_new_product_price),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        is AiChatMessage.AssistantSaleRecorded -> {
            val balance = message.balanceAfterCents
            val name = message.customerName
            // La venta por CuentivoAI también dice en qué quedó la deuda (v44): "Venta registrada
            // con éxito" a secas dejaba a la persona sin saber cuánto debe ahora.
            val text = if (balance != null && !name.isNullOrBlank()) {
                stringResource(
                    R.string.ai_sale_recorded_success_balance,
                    when {
                        balance > 0L -> stringResource(R.string.message_balance_owes, name, formatCents(balance))
                        balance < 0L -> stringResource(R.string.message_balance_credit, name, formatCents(-balance))
                        else -> stringResource(R.string.message_balance_settled, name)
                    },
                )
            } else {
                stringResource(R.string.ai_sale_recorded_success)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        AiChatMessage.AssistantSaleStale -> Text(
            text = stringResource(R.string.ai_sale_stale_message),
            style = MaterialTheme.typography.bodyMedium,
        )
        AiChatMessage.AssistantSaleRejected -> Text(
            text = stringResource(R.string.ai_sale_rejected_message),
            style = MaterialTheme.typography.bodyMedium,
        )
        AiChatMessage.AssistantSaleLimitReached -> Text(
            text = stringResource(R.string.ai_sale_limit_reached_message),
            style = MaterialTheme.typography.bodyMedium,
        )
        is AiChatMessage.AssistantQueryAnswer -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (val result = message.result) {
                is AiBusinessQueryResult.Answer.CustomerBalance -> {
                    Text(
                        text = result.customerName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val balanceFormatted = formatCents(result.balanceCents, absolute = true)
                    val balanceText = when {
                        result.balanceCents > 0L -> stringResource(R.string.ai_query_customer_balance_format, balanceFormatted)
                        result.balanceCents == 0L -> stringResource(R.string.ai_query_customer_no_debt, result.customerName)
                        else -> stringResource(R.string.ai_query_customer_credit_format, balanceFormatted)
                    }
                    Text(
                        text = balanceText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is AiBusinessQueryResult.Answer.CustomersWithDebt -> {
                    Text(
                        text = stringResource(R.string.ai_query_debtors_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (result.rows.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ai_query_no_debtors),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        result.rows.forEach { row ->
                            Text(
                                text = stringResource(
                                    R.string.ai_query_debtor_row_format,
                                    row.customerName,
                                    formatCents(row.balanceCents),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (result.rows.size >= 20) {
                            Text(
                                text = stringResource(R.string.ai_query_debtors_top_20_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(R.string.ai_query_total_debt_all_customers, formatCents(result.totalCents)),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
                is AiBusinessQueryResult.Answer.SalesForPeriod -> {
                    val startStr = LocalDate.ofEpochDay(result.startEpochDay).toString()
                    val endStr = LocalDate.ofEpochDay(result.endEpochDay).toString()
                    Text(
                        text = stringResource(R.string.ai_query_sales_period_title, startStr, endStr),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (result.soldCents == 0L && result.collectedCents == 0L) {
                        Text(
                            text = stringResource(R.string.ai_query_sales_period_empty),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.ai_query_sales_period_sold, formatCents(result.soldCents)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.ai_query_sales_period_collected, formatCents(result.collectedCents)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is AiBusinessQueryResult.CustomerSelectionRequired -> {
                    Text(
                        text = stringResource(R.string.ai_query_customer_selection_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    result.candidates.forEach { candidate ->
                        Button(
                            onClick = { onSelectQueryCustomerCandidate?.invoke(candidate.customerId) },
                            enabled = onSelectQueryCustomerCandidate != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(candidate.displayName)
                        }
                    }
                }
                is AiBusinessQueryResult.CustomerNotFound -> {
                    Text(
                        text = stringResource(R.string.ai_query_customer_not_found, result.queriedName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is AiBusinessQueryResult.Blocked -> {
                    val blockedText = when (result.reason) {
                        com.cuentivo.app.ai.AiQueryBlockReason.INVALID_DATE -> stringResource(R.string.ai_query_blocked_invalid_date)
                        com.cuentivo.app.ai.AiQueryBlockReason.START_AFTER_END -> stringResource(R.string.ai_query_blocked_start_after_end)
                        com.cuentivo.app.ai.AiQueryBlockReason.PERIOD_TOO_LONG -> stringResource(R.string.ai_query_blocked_period_too_long)
                    }
                    Text(
                        text = blockedText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                AiBusinessQueryResult.Unavailable -> {
                    Text(
                        text = stringResource(R.string.ai_debug_lab_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * La vista previa de una venta del borrador de CuentivoAI, extraída de [ChatMessageRow] para que una
 * prueba Compose pueda renderizar exactamente el mismo camino de producción que ve la persona.
 *
 * Un producto que la persona acaba de nombrar (`resolutionMode == NEW`) no tiene precio habitual,
 * así que esta vista nunca imprime la fila "Precio habitual": anunciarla inventaba una historia que
 * el producto no tiene. Si esa fila volviera a este composable, la prueba conductual se pondría roja.
 */
@Composable
fun AiSalePreviewContent(
    preview: AiResolvedSalePreview,
    onConfirmSale: (() -> Unit)? = null,
    onCancelSale: (() -> Unit)? = null,
    saleDueDateDays: Long? = null,
    onUpdateSaleDueDateDays: ((Long?) -> Unit)? = null,
    isConfirming: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.ai_debug_chat_draft_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        val customerBadge = stringResource(
            if (preview.customerMode == com.cuentivo.app.ai.AiEntityResolutionMode.NEW) {
                R.string.ai_entity_badge_new
            } else {
                R.string.ai_entity_badge_existing
            },
        )
        Text(
            text = stringResource(
                R.string.ai_sale_preview_customer_format,
                preview.customerName,
                customerBadge,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        preview.lines.forEach { line ->
            val productBadge = stringResource(
                if (line.resolutionMode == com.cuentivo.app.ai.AiEntityResolutionMode.NEW) {
                    R.string.ai_entity_badge_new
                } else {
                    R.string.ai_entity_badge_existing
                },
            )
            Text(
                text = stringResource(
                    R.string.ai_debug_lab_line_format,
                    line.productName + " (" + productBadge + ")",
                    line.quantity,
                    formatCents(line.lineTotalCents),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            // No "Precio habitual" line for a product the person just named. A brand-new product
            // has no habitual price, and the number the draft carries is the one they said — so
            // announcing it as habitual was inventing a history the product does not have.
        }
        Text(
            text = stringResource(
                R.string.ai_sale_preview_total,
                formatCents(preview.totalCents),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (preview.initialPaymentCents > 0L) {
            Text(
                text = stringResource(
                    R.string.ai_sale_preview_initial_payment,
                    formatCents(preview.initialPaymentCents),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.ai_sale_preview_remainder,
                    formatCents(preview.remainderCents),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        preview.promisedPaymentCandidate?.let { promise ->
            val date = promise as? com.cuentivo.app.ai.PromisedPaymentCandidate.DateCandidate
            if (date != null) {
                Text(
                    stringResource(R.string.ai_debug_lab_promised_date, "${date.year}-${date.month}-${date.day}"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // El plazo del borrador, antes de confirmar. Antes la venta por CuentivoAI nacía sin
        // fecha límite salvo que la persona la dijera en el texto, así que nunca vencía ni
        // entraba al recordatorio (v44). Mismo selector que la venta manual.
        if (onUpdateSaleDueDateDays != null) {
            Text(
                text = stringResource(R.string.new_sale_due_question),
                style = MaterialTheme.typography.labelMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DueOption.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { option ->
                            NeoChoiceChip(
                                label = stringResource(option.labelRes),
                                isSelected = saleDueDateDays == option.days,
                                onClick = { onUpdateSaleDueDateDays(option.days) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("ai-sale-due-option-" + option.name),
                            )
                        }
                    }
                }
            }
        }
        if (onConfirmSale != null && onCancelSale != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onConfirmSale,
                    enabled = !isConfirming,
                ) {
                    Text(stringResource(R.string.ai_sale_confirm_action))
                }
                TextButton(
                    onClick = onCancelSale,
                    enabled = !isConfirming,
                ) {
                    Text(stringResource(R.string.ai_sale_confirm_cancel))
                }
            }
        }
    }
}

@Composable
private fun ProposalPreviewContent(proposal: AiProposal) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (proposal) {
            is CreateSaleProposal -> {
                Text(stringResource(R.string.ai_debug_lab_proposal_sale), style = MaterialTheme.typography.titleMedium)
                Text(proposal.customerName)
                proposal.lines.forEach { line ->
                    Text(
                        stringResource(
                            R.string.ai_debug_lab_line_format,
                            line.productName,
                            line.quantity,
                            formatCents(line.unitPriceCents),
                        ),
                    )
                }
                Text(stringResource(R.string.ai_debug_lab_declared_payment, formatCents(proposal.declaredInitialPaymentCents)))
                proposal.promisedPaymentCandidate?.let { promise ->
                    val date = promise as? com.cuentivo.app.ai.PromisedPaymentCandidate.DateCandidate
                    if (date != null) {
                        Text(stringResource(R.string.ai_debug_lab_promised_date, "${date.year}-${date.month}-${date.day}"))
                    }
                }
            }
            is RecordPaymentProposal -> {
                Text(stringResource(R.string.ai_debug_lab_proposal_payment), style = MaterialTheme.typography.titleMedium)
                Text(proposal.customerName)
                Text(stringResource(R.string.ai_debug_lab_declared_payment, formatCents(proposal.declaredAmountCents)))
            }
            is BusinessQueryProposal -> {
                val queryTitle = when (proposal.queryType) {
                    com.cuentivo.app.ai.BusinessQueryType.CUSTOMER_BALANCE -> stringResource(R.string.ai_query_title_balance)
                    com.cuentivo.app.ai.BusinessQueryType.CUSTOMERS_WITH_DEBT -> stringResource(R.string.ai_query_title_debtors)
                    com.cuentivo.app.ai.BusinessQueryType.SALES_FOR_PERIOD -> stringResource(R.string.ai_query_title_sales)
                }
                Text(queryTitle, style = MaterialTheme.typography.titleMedium)
                proposal.customerName?.let { Text(it) }
                proposal.period?.let {
                    val start = "${it.startYear}-${it.startMonth}-${it.startDay}"
                    val end = "${it.endYear}-${it.endMonth}-${it.endDay}"
                    Text(stringResource(R.string.ai_query_proposal_period_format, start, end))
                }
            }
            is com.cuentivo.app.ai.RequiresClarificationProposal -> Unit
        }
    }
}

/** Candidate-only centavo formatting; the domain still computes any real totals. */
internal fun formatCents(cents: Long, absolute: Boolean = false): String {
    val decimal = BigDecimal.valueOf(cents, 2)
    val formatted = if (absolute) decimal.abs() else decimal
    return "$" + formatted.toPlainString()
}

/**
 * How long the informational notice stays on screen before fading out. Long enough to be read
 * without hurrying by someone who does not read fast, short enough to stop being furniture.
 */
private const val NOTICE_VISIBLE_MILLIS = 7000L

@Composable
private fun VoiceWaveformBars(
    level: Float,
    modifier: Modifier = Modifier,
) {
    val barWeights = remember { listOf(0.4f, 0.7f, 1.0f, 0.8f, 0.5f) }
    Row(
        modifier = modifier.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        barWeights.forEach { weight ->
            val targetFraction = (0.2f + 0.8f * (level * weight)).coerceIn(0.2f, 1f)
            val animatedFraction by animateFloatAsState(
                targetValue = targetFraction,
                label = "waveform-bar",
            )
            val barHeight = (24.dp * animatedFraction).coerceAtLeast(4.dp)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

/**
 * What is already registered around the days this page talks about.
 *
 * The range is taken from the **effective** date of each row — the row's own date, or the page date
 * when the row has none — widened by a couple of weeks on each side so a sheet re-photographed after
 * a correction still lines up. That definition lives in `LedgerEffectiveDate`, shared with the
 * detector, so the query and the comparison cannot disagree (v44). Only when no row has any usable
 * date does it fall back to today, which is the only date anybody knows in that case.
 */
private suspend fun loadRegisteredAround(
    resolver: (suspend (businessId: String, minEpochDay: Long, maxEpochDay: Long) -> List<com.cuentivo.app.domain.RegisteredOperationProjection>)?,
    businessId: String,
    operations: List<com.cuentivo.app.ai.ledger.LedgerOperation>,
    pageDateEpochDay: Long? = null,
): List<com.cuentivo.app.domain.RegisteredOperationProjection> {
    if (resolver == null) return emptyList()
    val today = java.time.LocalDate.now().toEpochDay()
    val (min, max) = com.cuentivo.app.domain.LedgerEffectiveDate.queryWindow(
        operations = operations,
        pageDateEpochDay = pageDateEpochDay,
        todayEpochDay = today,
        windowDays = DUPLICATE_WINDOW_DAYS,
    )
    return runCatching { resolver(businessId, min, max) }.getOrDefault(emptyList())
}

private const val DUPLICATE_WINDOW_DAYS = 14L
