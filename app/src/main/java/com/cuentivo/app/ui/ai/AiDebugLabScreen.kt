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
import com.cuentivo.app.ai.AiInterpretRequest
import com.cuentivo.app.ai.AiProposal
import com.cuentivo.app.ai.BusinessQueryProposal
import com.cuentivo.app.ai.CreateSaleProposal
import com.cuentivo.app.ai.RecordPaymentProposal
import com.cuentivo.app.ai.capture.AiCapturePolicy
import com.cuentivo.app.ai.capture.AiCaptureStore
import com.cuentivo.app.ai.capture.AiOcrEngine
import com.cuentivo.app.ui.product.CameraCaptureStore
import java.io.File
import java.math.BigDecimal
import java.util.UUID
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
 * Non-executable debug text lab. Edits text, sends ONE request through the injected transport and
 * shows the safe result: unavailable, clarification or a typed proposal preview. There is no
 * confirm/save/execute control and nothing connects to sales, payments, products, customers,
 * Room or any financial data. A visible warning states the lab records nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDebugLabScreen(
    transport: AiDebugTransport,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var model by remember { mutableStateOf(AiDebugLabModel()) }
    val scope = rememberCoroutineScope()
    var showSourceSelector by remember { mutableStateOf(false) }
    var cameraUnavailable by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val captureStore = remember(context) {
        com.cuentivo.app.ai.capture.AiCaptureStore(java.io.File(context.cacheDir, "ai-capture"))
    }
    val cameraStore = remember(context) { CameraCaptureStore(context) }
    var pendingCaptureUri by rememberSaveable(stateSaver = CaptureUriSaver) { mutableStateOf<Uri?>(null) }
    val ocrEngine = remember(context) { debugAiOcrEngine(context) }

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
                items(model.messages) { message -> ChatMessageRow(message) }
            }
            if (cameraUnavailable) {
                Text(
                    text = stringResource(R.string.ai_debug_chat_camera_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Bottom-aligned composer: text send uses the existing transport.
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = model.text,
                    onValueChange = { model = AiDebugLabMachine.onTextChanged(model, it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.ai_debug_lab_input_hint)) },
                    enabled = model.state != AiDebugLabState.Sending,
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = model.canSubmit,
                    onClick = {
                        val request = AiInterpretRequest(
                            text = model.text.trim(),
                            requestId = UUID.randomUUID().toString(),
                        )
                        val outcome = AiDebugLabMachine.onSubmit(model)
                        if (outcome is AiDebugLabSubmitOutcome.Submitted) {
                            model = outcome.model
                            val token = outcome.generation
                            scope.launch {
                                val result = AiDebugLabSubmitPolicy.run(transport, request)
                                model = AiDebugLabMachine.onResult(model, result, token)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.ai_debug_lab_send))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    pendingCaptureUri?.let(cameraStore::delete)
                    pendingCaptureUri = null
                    cameraStore.deleteStale(except = null)
                    model = AiDebugLabMachine.onReset(model)
                }) {
                    Text(stringResource(R.string.ai_debug_lab_new))
                }
                if (ocrEngine != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = model.state != AiDebugLabState.Sending,
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
    }
}

@Composable
private fun ChatMessageRow(message: AiChatMessage) {
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
