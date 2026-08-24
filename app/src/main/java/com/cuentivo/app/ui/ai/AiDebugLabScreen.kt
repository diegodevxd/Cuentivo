package com.cuentivo.app.ui.ai

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cuentivo.app.R
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
import com.cuentivo.app.ai.BusinessQueryProposal
import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.CreateSaleProposal
import com.cuentivo.app.ai.RecordPaymentProposal
import com.cuentivo.app.ai.capture.AiCameraCaptureStore
import com.cuentivo.app.ai.capture.AiCapturePolicy
import com.cuentivo.app.ai.capture.AiCaptureStore
import com.cuentivo.app.ai.capture.AiOcrEngine
import com.cuentivo.app.data.local.AiDailyChatRepository
import java.io.File
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    salePreviewResolver: (suspend (businessId: String, proposal: CreateSaleProposal) -> AiSalePreviewResult)? = null,
    activeBusinessId: String? = null,
    confirmPayment: (suspend (AiPaymentConfirmationRequest) -> AiPaymentExecutionResult)? = null,
    confirmSale: (suspend (AiSaleConfirmationRequest) -> AiSaleExecutionResult)? = null,
) {
    var model by remember { mutableStateOf(AiDebugLabModel()) }
    var isHydrated by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showSourceSelector by remember { mutableStateOf(false) }
    var cameraUnavailable by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val captureStore = remember(context) {
        com.cuentivo.app.ai.capture.AiCaptureStore(java.io.File(context.cacheDir, "ai-capture"))
    }
    val cameraStore = remember(context) { AiCameraCaptureStore(context) }
    var pendingCaptureUri by rememberSaveable(stateSaver = CaptureUriSaver) { mutableStateOf<Uri?>(null) }
    val ocrEngine = remember(context) { debugAiOcrEngine(context) }

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

    // Sweeps captures orphaned by an earlier dialog when this one opens, not in onDispose:
    // leaving composition can mean the Activity was destroyed while the camera app was still
    // writing to that very file, and deleting it there threw away the photo the user just took.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { cameraStore.deleteStale(except = pendingCaptureUri) }
    }

    // One close action: cancel the in-flight request (generation bump) BEFORE closing, so a late
    // transport result is always invalidated. Also sweeps all capture temp files.
    val closeLab = {
        model = AiDebugLabMachine.onCancel(model)
        captureStore.cleanupAll()
        pendingCaptureUri?.let(cameraStore::delete)
        pendingCaptureUri = null
        cameraStore.deleteStale(except = null)
        onClose()
    }

    BackHandler(onBack = closeLab)

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val token = model.generation
            val recognizedText = withContext(Dispatchers.IO) {
                // MIME from the actual content, not assumed: a provider can label anything.
                val mimeType = context.contentResolver.getType(uri)
                if (!AiCapturePolicy.isAllowedMime(mimeType)) {
                    return@withContext null
                }
                val captureFile = captureStore.createCaptureFile(mimeType!!)
                if (captureFile == null) {
                    return@withContext null
                }
                try {
                    val input = context.contentResolver.openInputStream(uri)
                    if (input == null) {
                        return@withContext null
                    }
                    input.use { stream ->
                        captureFile.outputStream().use { output -> stream.copyTo(output) }
                    }
                    if (!AiCapturePolicy.isWithinByteCap(captureFile.length())) {
                        return@withContext null
                    }
                    // Dimension bounds check without loading the full bitmap into memory.
                    val bounds = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(captureFile.absolutePath, bounds)
                    if (!AiCapturePolicy.isWithinDimensions(bounds.outWidth, bounds.outHeight)) {
                        return@withContext null
                    }
                    ocrEngine?.recognize(captureFile)
                } catch (_: Exception) {
                    null
                } finally {
                    captureStore.deleteCapture(captureFile)
                }
            }
            model = AiDebugLabMachine.onOcrResult(model, recognizedText, token)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCaptureUri ?: return@rememberLauncherForActivityResult
        pendingCaptureUri = null
        if (!success) {
            cameraStore.delete(uri)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val token = model.generation
            val recognizedText = withContext(Dispatchers.IO) {
                val file = cameraStore.fileFor(uri)
                try {
                    if (!AiCapturePolicy.isWithinByteCap(file.length())) {
                        return@withContext null
                    }
                    val bounds = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                    if (!AiCapturePolicy.isWithinDimensions(bounds.outWidth, bounds.outHeight)) {
                        return@withContext null
                    }
                    ocrEngine?.recognize(file)
                } catch (_: Exception) {
                    null
                } finally {
                    cameraStore.delete(uri)
                }
            }
            model = AiDebugLabMachine.onOcrResult(model, recognizedText, token)
        }
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
            Text(
                text = stringResource(R.string.ai_debug_lab_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
                    val isConfirming = model.state is AiDebugLabState.PaymentConfirming || model.state is AiDebugLabState.SaleConfirming
                    ChatMessageRow(
                        message = message,
                        onConfirmPayment = if (isCurrentPaymentPreview && isHydrated && confirmPayment != null && activeBusinessId != null) {
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
                        onConfirmSale = if (isCurrentSalePreview && isHydrated && confirmSale != null && activeBusinessId != null) {
                            {
                                val outcome = AiDebugLabMachine.onConfirmSale(model, activeBusinessId)
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
                        onCancelSale = if (isCurrentSalePreview && isHydrated) {
                            { model = AiDebugLabMachine.onCancel(model) }
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
            // Bottom-aligned composer: text send uses the turn policy.
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = model.text,
                    onValueChange = { model = AiDebugLabMachine.onTextChanged(model, it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.ai_debug_lab_input_hint)) },
                    enabled = isHydrated && model.state != AiDebugLabState.Sending,
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = isHydrated && model.canSubmit,
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
                                        if (result is CallableAiProposalResult.Unavailable) {
                                            withContext(Dispatchers.IO) {
                                                runCatching { repository.appendAssistant(AiDailyMessageKind.ASSISTANT_UNAVAILABLE) }
                                            }
                                        }
                                        val nextModel = AiDebugLabMachine.onResult(model, result, token)
                                        model = nextModel
                                        if (result is CallableAiProposalResult.Proposal &&
                                            result.proposal is RecordPaymentProposal &&
                                            nextModel.state is AiDebugLabState.ProposalPreview
                                        ) {
                                            val paymentProposal = result.proposal
                                            val resolutionResult = if (paymentPreviewResolver != null && activeBusinessId != null) {
                                                withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        paymentPreviewResolver(activeBusinessId, paymentProposal)
                                                    }.getOrDefault(AiPaymentPreviewResult.Unavailable)
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
                                            val resolutionResult = if (salePreviewResolver != null && activeBusinessId != null) {
                                                withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        salePreviewResolver(activeBusinessId, saleProposal)
                                                    }.getOrDefault(AiSalePreviewResult.Unavailable)
                                                }
                                            } else {
                                                AiSalePreviewResult.Unavailable
                                            }
                                            model = AiDebugLabMachine.onSalePreviewResult(model, resolutionResult, token)
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.ai_debug_lab_send))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = isHydrated && model.state != AiDebugLabState.Sending,
                    onClick = { showClearDialog = true },
                ) {
                    Text(stringResource(R.string.ai_daily_chat_clear_today))
                }
                if (ocrEngine != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = isHydrated && model.state != AiDebugLabState.Sending,
                        onClick = { showSourceSelector = true },
                    ) {
                        Text(stringResource(R.string.ai_debug_lab_capture))
                    }
                }
            }
        }
        if (showSourceSelector) {
            ModalBottomSheet(onDismissRequest = { showSourceSelector = false }) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    TextButton(
                        onClick = {
                            showSourceSelector = false
                            cameraUnavailable = false
                            cameraStore.deleteStale(except = pendingCaptureUri)
                            val attempt = runCatching {
                                val uri = cameraStore.createCaptureUri()
                                pendingCaptureUri = uri
                                cameraLauncher.launch(uri)
                            }
                            if (attempt.isFailure) {
                                pendingCaptureUri?.let(cameraStore::delete)
                                pendingCaptureUri = null
                                cameraUnavailable = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ai_debug_chat_camera)) }
                    TextButton(
                        onClick = {
                            showSourceSelector = false
                            pickImageLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ai_debug_chat_gallery)) }
                    TextButton(
                        onClick = { showSourceSelector = false },
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
                            pendingCaptureUri?.let(cameraStore::delete)
                            pendingCaptureUri = null
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
        AiChatMessage.AssistantOutOfScope -> Text(stringResource(R.string.ai_daily_chat_out_of_scope))
        AiChatMessage.AssistantPending -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_debug_lab_thinking))
        }
        AiChatMessage.AssistantUnavailable -> Text(stringResource(R.string.ai_debug_lab_unavailable))
        is AiChatMessage.AssistantClarification -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ai_debug_lab_clarification))
            Text(message.reason.name, style = MaterialTheme.typography.titleMedium)
            Text(message.slots.joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
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
                AiPaymentBlockReason.NO_OUTSTANDING_DEBT -> Text(
                    text = stringResource(R.string.ai_payment_preview_blocked_no_debt),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AiPaymentBlockReason.AMOUNT_EXCEEDS_DEBT -> {
                    val debtFormatted = message.preview?.let { formatCents(it.currentBalanceCents) }.orEmpty()
                    Text(
                        text = stringResource(R.string.ai_payment_preview_blocked_exceeds_debt, debtFormatted),
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
        is AiChatMessage.AssistantSalePreview -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.ai_debug_chat_draft_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message.preview.customerName,
                style = MaterialTheme.typography.titleMedium,
            )
            message.preview.lines.forEach { line ->
                Text(
                    text = stringResource(
                        R.string.ai_debug_lab_line_format,
                        line.productName,
                        line.quantity,
                        formatCents(line.lineTotalCents),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = stringResource(
                    R.string.ai_sale_preview_total,
                    formatCents(message.preview.totalCents),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (message.preview.initialPaymentCents > 0L) {
                Text(
                    text = stringResource(
                        R.string.ai_sale_preview_initial_payment,
                        formatCents(message.preview.initialPaymentCents),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.ai_sale_preview_remainder,
                        formatCents(message.preview.remainderCents),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            message.preview.promisedPaymentCandidate?.let { promise ->
                val date = promise as? com.cuentivo.app.ai.PromisedPaymentCandidate.DateCandidate
                if (date != null) {
                    Text(
                        stringResource(R.string.ai_debug_lab_promised_date, "${date.year}-${date.month}-${date.day}"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
        AiChatMessage.AssistantSaleCustomerNotFound -> Text(
            text = stringResource(R.string.ai_payment_preview_customer_not_found),
            style = MaterialTheme.typography.bodyMedium,
        )
        is AiChatMessage.AssistantSaleCustomerSelection -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        is AiChatMessage.AssistantSaleProductNotFound -> Text(
            text = stringResource(R.string.ai_sale_preview_product_not_found, message.productName),
            style = MaterialTheme.typography.bodyMedium,
        )
        is AiChatMessage.AssistantSaleProductSelection -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.ai_sale_preview_product_selection_required, message.requestedName),
                style = MaterialTheme.typography.bodyMedium,
            )
            message.candidates.forEach { candidate ->
                Text(
                    text = "• " + candidate.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        is AiChatMessage.AssistantSaleBlocked -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (message.reason) {
                AiSaleBlockReason.INITIAL_PAYMENT_EXCEEDS_TOTAL -> {
                    val initialFormatted = message.preview?.let { formatCents(it.initialPaymentCents) }.orEmpty()
                    val totalFormatted = message.preview?.let { formatCents(it.totalCents) }.orEmpty()
                    Text(
                        text = stringResource(R.string.ai_sale_preview_blocked_overpayment, initialFormatted, totalFormatted),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                AiSaleBlockReason.ARITHMETIC_OVERFLOW -> Text(
                    text = stringResource(R.string.ai_sale_preview_blocked_overflow),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AiSaleBlockReason.INVALID_LINE_PRICE,
                AiSaleBlockReason.INVALID_LINE_QUANTITY -> Text(
                    text = stringResource(R.string.ai_sale_preview_blocked_invalid_line),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        is AiChatMessage.AssistantSaleRecorded -> Text(
            text = stringResource(R.string.ai_sale_recorded_success),
            style = MaterialTheme.typography.bodyMedium,
        )
        AiChatMessage.AssistantSaleStale -> Text(
            text = stringResource(R.string.ai_sale_stale_message),
            style = MaterialTheme.typography.bodyMedium,
        )
        AiChatMessage.AssistantSaleRejected -> Text(
            text = stringResource(R.string.ai_sale_rejected_message),
            style = MaterialTheme.typography.bodyMedium,
        )
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
                // The query shows intent only, never a financial result.
                Text(stringResource(R.string.ai_debug_lab_proposal_query), style = MaterialTheme.typography.titleMedium)
                Text(proposal.queryType.name)
                proposal.customerName?.let { Text(it) }
            }
            is com.cuentivo.app.ai.RequiresClarificationProposal -> Unit
        }
    }
}

/** Candidate-only centavo formatting; the domain still computes any real totals. */
private fun formatCents(cents: Long): String = "$" + BigDecimal.valueOf(cents, 2).toPlainString()
