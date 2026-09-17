package com.cuentivo.app.ui.ai

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.ai.ledger.CustomerReviewGroup
import com.cuentivo.app.ai.ledger.FinancialRejectionReason
import com.cuentivo.app.ai.ledger.LedgerAmountInput
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReview
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.OperationFinancialDiagnostic
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation
import com.cuentivo.app.domain.FieldHealthColor
import com.cuentivo.app.domain.LedgerFieldHealth
import com.cuentivo.app.domain.LedgerImportSummaryCalculator
import com.cuentivo.app.domain.ProductCandidate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure review screen for imported ledger operations.
 * Renders state derived by [LedgerReview] and forwards user interactions to machine callbacks.
 * Strictly adheres to: "Ninguna decisión vive en el Composable."
 */
@Composable
fun LedgerReviewContent(
    review: LedgerReview,
    captureFile: File?,
    onUpdateAmount: (lineIndex: Int, amountCents: String) -> Unit,
    onUpdateCustomer: (lineIndex: Int, customerName: String) -> Unit,
    onUpdateKind: (lineIndex: Int, kind: ProposedLedgerKind) -> Unit,
    onUpdateDate: (lineIndex: Int, date: SafeDateCandidate?) -> Unit,
    onUpdatePageDate: (pageDateEpochDay: Long?) -> Unit,
    onRemoveRow: (lineIndex: Int) -> Unit,
    onConfirmObjection: (lineIndex: Int) -> Unit,
    /** Existing customers this page's rows resemble, keyed by lineIndex. Offers, never decisions. */
    customerMatches: Map<Int, com.cuentivo.app.domain.EntityMatchResult<com.cuentivo.app.domain.CustomerCandidate>>,
    onAcceptCustomerAnchor: (lineIndex: Int, candidate: com.cuentivo.app.domain.CustomerCandidate) -> Unit = { _, _ -> },
    /** Catalog products this page's rows resemble, keyed by lineIndex. Offers product name for description, never changes amount. */
    productMatches: Map<Int, com.cuentivo.app.domain.EntityMatchResult<ProductCandidate>>,
    onAcceptProductAnchor: (lineIndex: Int, candidate: ProductCandidate) -> Unit = { _, _ -> },
    /** Potential duplicate operations already in Room or elsewhere on this page, keyed by lineIndex. */
    duplicateMatches: Map<Int, com.cuentivo.app.domain.LedgerDuplicateMatch>,
    /** Objeciones ya calculadas con las listas completas de candidatos. */
    objections: List<com.cuentivo.app.domain.LedgerDeterministicObjection>,
    isRegistrationBlockedByLegacySave: Boolean,
    isPageCompletenessConfirmed: Boolean,
    onConfirmPageCompleteness: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingOperation by remember { mutableStateOf<LedgerOperation?>(null) }
    // The rows still waiting to be confirmed, as line indices. Kept as indices and not as objects
    // because every edit rebuilds the review, and the object held here would go stale mid-walk.
    var pendingReviewQueue by remember { mutableStateOf<List<Int>>(emptyList()) }
    val hasRowsWithoutDate = review.operations.filter { isRegisterableLedgerOperation(it) }.any { it.date == null }
    // What the model read but would not vouch for. Registering one of these without a person having
    // looked at it is the whole thing this feature must never do, so they stay out of the count --
    // but leaving the person to discover, unaided, that every row needs a tap is how a working
    // import looked like a dead button.
    val objectionLineIndices = remember(objections) { objections.map { it.lineIndex }.toSet() }
    val allRowsNeedingReview = remember(review.operations, objections) {
        review.operations.filter { !isRegisterableLedgerOperation(it) || it.lineIndex in objectionLineIndices }
    }

    val summaryResult = LedgerImportSummaryCalculator.calculate(review, objections, isPageCompletenessConfirmed)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ai_ledger_import_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("ledger-review-cancel-button"),
            ) {
                Text(stringResource(R.string.ai_ledger_action_discard))
            }
        }

        // Summary Card
        LedgerSummaryCard(
            review = review,
            objections = objections,
            modifier = Modifier.fillMaxWidth(),
        )

        // Page date selector: shown ONLY when at least one registrable operation lacks a date
        if (hasRowsWithoutDate) {
            PageDateCard(
                pageDateEpochDay = review.pageDateEpochDay,
                detectedPageDate = review.detectedPageDate,
                onUpdatePageDate = onUpdatePageDate,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // A page that did not fit, or whose text the model could not finish reading, must say so
        // here. Confirming a partial import silently is how a notebook ends up half migrated with
        // nobody aware which half is missing.
        if (review.pageIncomplete || review.unreadTextRemaining) {
            Text(
                text = stringResource(R.string.ai_ledger_page_incomplete_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ledger-page-incomplete-warning"),
            )
        }

        // Financial & Date Block Alert (if any)
        // Also when some rows are fine and others are not: a page that registers 8 of its 20 rows
        // and says nothing about the other 12 is the quiet half-import this screen exists to prevent.
        if (review.summary.hasFinancialErrors || review.summary.executableOperationsCount == 0 ||
            review.summary.hasMissingDates || allRowsNeedingReview.isNotEmpty()
        ) {
            FinancialErrorBanner(
                diagnostics = review.summary.financialDiagnostics,
                hasZeroOps = review.summary.executableOperationsCount == 0,
                hasMissingDates = review.summary.hasMissingDates,
                rowsNeedingReviewCount = allRowsNeedingReview.size,
                onStartReview = {
                    pendingReviewQueue = allRowsNeedingReview.map { it.lineIndex }.drop(1)
                    editingOperation = allRowsNeedingReview.firstOrNull()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Customer groups and operations list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Computed once for the page, not per row: the evaluator is pure and the diagnostics
            // it needs belong to the whole review.
            val fieldHealth = review.operations.associate { op ->
                op.lineIndex to LedgerFieldHealth.evaluate(
                    op = op,
                    financialDiagnostics = review.summary.financialDiagnostics,
                    pageDateEpochDay = review.pageDateEpochDay,
                    todayEpochDay = review.todayEpochDay,
                    objections = objections,
                )
            }
            items(review.customerGroups) { group ->
                CustomerGroupCard(
                    group = group,
                    captureFile = captureFile,
                    fieldHealth = fieldHealth,
                    duplicateMatches = duplicateMatches,
                    onEditOperation = { op -> editingOperation = op },
                    onUpdateKind = onUpdateKind,
                    onRemoveRow = onRemoveRow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Single confirmation button at the bottom
        Button(
            onClick = onConfirm,
            enabled = summaryResult.isConfirmButtonEnabled && !isRegistrationBlockedByLegacySave,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ledger-review-confirm-button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            val buttonText = if (summaryResult.isConfirmButtonEnabled) {
                pluralStringResource(
                    R.plurals.ai_ledger_action_register_count,
                    summaryResult.readyCount,
                    summaryResult.readyCount,
                )
            } else {
                if (summaryResult.unresolvedCount > 0) {
                    pluralStringResource(R.plurals.ai_ledger_action_register_blocked_pending, summaryResult.unresolvedCount, summaryResult.unresolvedCount)
                } else if (summaryResult.pageIncompleteNoticeRequired && !isPageCompletenessConfirmed) {
                    stringResource(R.string.ai_ledger_action_register_blocked_incomplete)
                } else stringResource(R.string.ai_ledger_action_register_disabled)
            }
            Text(
                text = buttonText,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    if (isRegistrationBlockedByLegacySave) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.ai_ledger_legacy_save_blocked))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.ai_ledger_legacy_save_discard)) }
            }
        }
    }

    if (summaryResult.pageIncompleteNoticeRequired && !isPageCompletenessConfirmed) {
        Button(onClick = onConfirmPageCompleteness, modifier = Modifier.testTag("ledger-confirm-page-complete-button")) {
            Text(stringResource(R.string.ai_ledger_action_confirm_page_complete))
        }
    } else if (summaryResult.pageIncompleteNoticeRequired) {
        Text(stringResource(R.string.ai_ledger_page_completeness_confirmed), modifier = Modifier.testTag("ledger-page-completeness-confirmed"))
    }

    // Edit modal dialog when a row or unresolved field is tapped
    editingOperation?.let { op ->
        EditLedgerOperationDialog(
            operation = op,
            captureFile = captureFile,
            customerMatch = customerMatches[op.lineIndex],
            productMatch = productMatches[op.lineIndex],
            duplicateMatch = duplicateMatches[op.lineIndex],
            objections = objections,
            onConfirmObjection = {
                onConfirmObjection(op.lineIndex)
                val nextLineIndex = pendingReviewQueue.firstOrNull()
                pendingReviewQueue = pendingReviewQueue.drop(1)
                editingOperation = nextLineIndex?.let { next ->
                    review.operations.firstOrNull { it.lineIndex == next }
                }
            },
            onAcceptCustomerAnchor = { candidate ->
                onAcceptCustomerAnchor(op.lineIndex, candidate)
                val nextLineIndex = pendingReviewQueue.firstOrNull()
                pendingReviewQueue = pendingReviewQueue.drop(1)
                editingOperation = nextLineIndex?.let { next ->
                    review.operations.firstOrNull { it.lineIndex == next }
                }
            },
            onAcceptProductAnchor = { candidate ->
                onAcceptProductAnchor(op.lineIndex, candidate)
            },
            onDismiss = {
                editingOperation = null
                pendingReviewQueue = emptyList()
            },
            onSave = { updatedName, updatedAmount, updatedKind, updatedDate ->
                // Saving is an explicit confirmation of all three fields, not a diff. Someone who
                // opens a doubtful row, checks it against the crop and saves has resolved the
                // doubt even when they changed nothing; forwarding only what differs would leave
                // that row out with no way to ever let it in.
                onUpdateCustomer(op.lineIndex, updatedName)
                onUpdateAmount(op.lineIndex, updatedAmount)
                onUpdateKind(op.lineIndex, updatedKind)
                onUpdateDate(op.lineIndex, updatedDate)
                // Straight on to the next doubtful row. Twenty rows means twenty confirmations
                // either way -- what changes is whether the person has to hunt for each one.
                val nextLineIndex = pendingReviewQueue.firstOrNull()
                pendingReviewQueue = pendingReviewQueue.drop(1)
                editingOperation = nextLineIndex?.let { next ->
                    review.operations.firstOrNull { it.lineIndex == next }
                }
            },
        )
    }
}

@Composable
private fun LedgerSummaryCard(
    review: LedgerReview,
    objections: List<com.cuentivo.app.domain.LedgerDeterministicObjection>,
    modifier: Modifier = Modifier,
) {
    val summaryResult = LedgerImportSummaryCalculator.calculate(review, objections, false)

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_ledger_review_summary_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val detectedText = pluralStringResource(
                R.plurals.ai_ledger_summary_detected_count,
                summaryResult.detectedCount,
                summaryResult.detectedCount,
            )
            Text(
                text = detectedText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            val readyText = pluralStringResource(
                R.plurals.ai_ledger_summary_ready_count,
                summaryResult.readyCount,
                summaryResult.readyCount,
            )
            val pendingText = pluralStringResource(
                R.plurals.ai_ledger_summary_pending_count,
                summaryResult.pendingReviewCount,
                summaryResult.pendingReviewCount,
            )
            Text(
                text = stringResource(R.string.ai_ledger_summary_status_format, readyText, pendingText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summaryResult.pageIncompleteNoticeRequired) {
                Text(
                    text = stringResource(R.string.ai_ledger_page_incomplete_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("ledger-summary-page-incomplete-notice"),
                )
            }
        }
    }
}

@Composable
private fun PageDateCard(
    pageDateEpochDay: Long?,
    detectedPageDate: com.cuentivo.app.ai.ledger.LedgerDetectedPageDate?,
    onUpdatePageDate: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dateText by remember {
        mutableStateOf(
            pageDateEpochDay?.let {
                val localDate = java.time.LocalDate.ofEpochDay(it)
                String.format("%04d-%02d-%02d", localDate.year, localDate.monthValue, localDate.dayOfMonth)
            } ?: ""
        )
    }
    val parsedDate = parseLedgerDateInput(dateText)
    val dateIsBlank = dateText.isBlank()
    val todayEpochDay = remember { java.time.LocalDate.now().toEpochDay() }
    val isValidDate = parsedDate != null && LedgerReviewEdits.isAcceptableDate(parsedDate, todayEpochDay)
    val dateIsWrong = !dateIsBlank && !isValidDate

    // The date the server read off the sheet, offered for a tap. Never applied on its own, not
    // even at confidence 0.99: a wrong date sends ten movements to a day that is not theirs, and
    // in an append-only ledger that is undone with ten compensating movements. Offering it is
    // help; writing it alone is guessing. Only a real, non-future calendar day is offered at all,
    // and only while the field is empty — once the person has typed something, their reading of
    // the paper outranks the model's.
    val offerableDetectedDate = detectedPageDate?.takeIf { dateIsBlank }?.let { detected ->
        val asCalendarDay = runCatching {
            java.time.LocalDate.of(detected.date.year, detected.date.month, detected.date.day)
        }.getOrNull()
        asCalendarDay?.takeIf { it.toEpochDay() <= todayEpochDay }?.let { detected }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_ledger_field_page_date),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = dateText,
                onValueChange = { newText ->
                    dateText = newText
                    val candidate = parseLedgerDateInput(newText)
                    if (candidate != null && LedgerReviewEdits.isAcceptableDate(candidate, todayEpochDay)) {
                        val epochDay = java.time.LocalDate.of(candidate.year, candidate.month, candidate.day).toEpochDay()
                        onUpdatePageDate(epochDay)
                    } else {
                        onUpdatePageDate(null)
                    }
                },
                label = { Text(stringResource(R.string.ai_ledger_field_page_date)) },
                placeholder = { Text(stringResource(R.string.ai_ledger_field_date_placeholder)) },
                isError = dateIsWrong,
                supportingText = if (dateIsWrong) {
                    { Text(stringResource(R.string.ai_ledger_field_page_date_invalid)) }
                } else null,
                modifier = Modifier.fillMaxWidth().testTag("ledger-page-date-field"),
                singleLine = true,
            )
            if (offerableDetectedDate != null) {
                val detectedCalendarDay = java.time.LocalDate.of(
                    offerableDetectedDate.date.year,
                    offerableDetectedDate.date.month,
                    offerableDetectedDate.date.day,
                )
                val detectedText = detectedCalendarDay.toString()
                // Low certainty is said in the offer itself, so whoever taps it knows they are
                // confirming something the model was unsure about rather than something settled.
                val offerText = if (offerableDetectedDate.confidence < LOW_PAGE_DATE_CONFIDENCE) {
                    stringResource(R.string.ai_ledger_page_date_detected_low_confidence, detectedText)
                } else {
                    stringResource(R.string.ai_ledger_page_date_detected, detectedText)
                }
                TextButton(
                    onClick = {
                        // The tap is the acceptance. Until it happens, the detected date has
                        // changed nothing: not the field, not the review, not a single row.
                        dateText = detectedText
                        onUpdatePageDate(detectedCalendarDay.toEpochDay())
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ledger-page-date-detected-offer"),
                ) {
                    Text(
                        text = offerText,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** Below this, a detected page date says so in its own offer text. */
private const val LOW_PAGE_DATE_CONFIDENCE = 0.5

@Composable
private fun FinancialErrorBanner(
    diagnostics: List<OperationFinancialDiagnostic>,
    hasZeroOps: Boolean,
    hasMissingDates: Boolean = false,
    rowsNeedingReviewCount: Int = 0,
    onStartReview: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag("ledger-financial-error-banner"),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hasZeroOps && diagnostics.isEmpty()) {
                Text(
                    text = if (rowsNeedingReviewCount > 0) {
                        stringResource(R.string.ai_ledger_error_rows_need_review, rowsNeedingReviewCount)
                    } else {
                        stringResource(R.string.ai_ledger_error_zero_operations)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (rowsNeedingReviewCount > 0) {
                TextButton(
                    onClick = onStartReview,
                    modifier = Modifier.testTag("ledger-start-review-button"),
                ) {
                    Text(
                        text = stringResource(R.string.ai_ledger_action_review_uncertain, rowsNeedingReviewCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (hasMissingDates && diagnostics.isEmpty() && !hasZeroOps) {
                Text(
                    text = stringResource(R.string.ai_ledger_error_missing_page_date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium,
                )
            }
            diagnostics.forEach { diag ->
                val message = when (diag.reason) {
                    FinancialRejectionReason.ZERO_AMOUNT -> stringResource(R.string.ai_ledger_error_zero_amount)
                    FinancialRejectionReason.OVERFLOW -> stringResource(R.string.ai_ledger_error_overflow)
                    FinancialRejectionReason.PAYMENT_WITHOUT_DEBT -> stringResource(R.string.ai_ledger_error_payment_without_debt)
                    FinancialRejectionReason.PAYMENT_EXCEEDS_DEBT -> stringResource(R.string.ai_ledger_error_payment_exceeds_debt)
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CustomerGroupCard(
    group: CustomerReviewGroup,
    captureFile: File?,
    fieldHealth: Map<Int, com.cuentivo.app.domain.OperationFieldHealth>,
    duplicateMatches: Map<Int, com.cuentivo.app.domain.LedgerDuplicateMatch>,
    onEditOperation: (LedgerOperation) -> Unit,
    onUpdateKind: (lineIndex: Int, kind: ProposedLedgerKind) -> Unit,
    onRemoveRow: (lineIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Customer header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.customerName.ifBlank { stringResource(R.string.ai_slot_customer) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // Badge: New vs Existing
                Surface(
                    color = if (group.isNew) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = if (group.isNew) {
                            stringResource(R.string.ai_ledger_customer_badge_new)
                        } else {
                            stringResource(R.string.ai_ledger_customer_badge_existing)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = if (group.isNew) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            // Balance transition: $prev -> $resulting
            val prevFormatted = formatCents(group.previousBalanceCents)
            val resFormatted = formatCents(group.resultingBalanceCents)
            Text(
                text = stringResource(R.string.ai_ledger_customer_balance_transition, prevFormatted, resFormatted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            // Rows
            group.operations.forEach { op ->
                LedgerOperationRow(
                    operation = op,
                    captureFile = captureFile,
                    health = fieldHealth[op.lineIndex],
                    duplicateMatch = duplicateMatches[op.lineIndex],
                    onEdit = { onEditOperation(op) },
                    onUpdateKind = { kind -> onUpdateKind(op.lineIndex, kind) },
                    onRemove = { onRemoveRow(op.lineIndex) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LedgerOperationRow(
    operation: LedgerOperation,
    captureFile: File?,
    health: com.cuentivo.app.domain.OperationFieldHealth? = null,
    duplicateMatch: com.cuentivo.app.domain.LedgerDuplicateMatch? = null,
    onEdit: () -> Unit,
    onUpdateKind: (ProposedLedgerKind) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUnresolved = operation.unreadableFields.isNotEmpty() || operation.uncertainFields.isNotEmpty()

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onEdit)
            .testTag("ledger-op-row-" + operation.lineIndex),
        color = if (isUnresolved) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Thumbnail Crop or "Sin recorte"
            RowCropThumbnail(
                captureFile = captureFile,
                operation = operation,
                modifier = Modifier.size(48.dp),
            )

            // Details
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val kindText = when (operation.proposedKind) {
                        ProposedLedgerKind.SALE -> stringResource(R.string.ai_ledger_kind_sale)
                        ProposedLedgerKind.PAYMENT -> stringResource(R.string.ai_ledger_kind_payment)
                        ProposedLedgerKind.PRIOR_DEBT -> stringResource(R.string.ai_ledger_kind_prior_debt)
                        null -> stringResource(R.string.ai_ledger_kind_unspecified)
                    }
                    Text(
                        text = kindText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (isUnresolved) {
                        Surface(
                            color = MaterialTheme.colorScheme.error,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = if (operation.unreadableFields.isNotEmpty()) {
                                    stringResource(R.string.ai_ledger_badge_unreadable)
                                } else {
                                    stringResource(R.string.ai_ledger_badge_uncertain)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    if (duplicateMatch != null) {
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.testTag("ledger-op-duplicate-badge-" + operation.lineIndex),
                        ) {
                            Text(
                                text = stringResource(R.string.ai_ledger_duplicate_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }

                // One badge for the whole row said something was wrong without saying where, so
                // every doubtful row looked equally broken and equally expensive to check. These
                // say which field to look at, which is the difference between reading a row and
                // glancing at it.
                // Only the fields that need something. Painting all four turned a page of sixteen
                // rows into sixteen screens: a long label like "Cliente (del renglon de arriba)" ate
                // the width, wrapped, and pushed every card to the height of the phone. A chip that
                // says "everything is fine" is also the one nobody reads, so it earns no space.
                if (health != null && health.needsAttention) {
                    val attention = listOfNotNull(
                        chipFor(stringResource(R.string.ai_ledger_field_short_customer), health.customerHealth, health.customerOrigin),
                        chipFor(stringResource(R.string.ai_ledger_field_short_amount), health.amountHealth, com.cuentivo.app.domain.FieldOrigin.READ_HERE),
                        chipFor(stringResource(R.string.ai_ledger_field_short_kind), health.kindHealth, com.cuentivo.app.domain.FieldOrigin.READ_HERE),
                        chipFor(stringResource(R.string.ai_ledger_field_short_date), health.dateHealth, health.dateOrigin),
                    )
                    if (attention.isNotEmpty()) {
                        Row(
                            modifier = Modifier.testTag("ledger-field-health-" + operation.lineIndex),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            attention.forEach { chip ->
                                FieldHealthChip(chip.label, chip.color, chip.inherited)
                            }
                        }
                    }
                }

                val amountCentsVal = operation.amountCents?.toLongOrNull()
                val amountText = if (amountCentsVal != null) {
                    formatCents(amountCentsVal)
                } else {
                    operation.amountCents ?: stringResource(R.string.ai_slot_amount)
                }
                // Amount and customer on one line: they are what a person scans for, and the two of
                // them stacked was half the height of a card that only ever holds one movement.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = amountText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (operation.customerName != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = operation.customerName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }

                // The date the notebook carries, shown even when it is missing: importing a year of
                // history as if it all happened today is exactly what this feature must not do.
                val rowDate = operation.date
                Text(
                    text = if (rowDate != null) {
                        stringResource(
                            R.string.ai_ledger_row_date,
                            String.format("%04d-%02d-%02d", rowDate.year, rowDate.month, rowDate.day),
                        )
                    } else {
                        stringResource(R.string.ai_ledger_row_date_missing)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            // Row actions: quick kind toggle & delete
            IconButton(
                onClick = {
                    val nextKind = when (operation.proposedKind) {
                        ProposedLedgerKind.SALE -> ProposedLedgerKind.PAYMENT
                        ProposedLedgerKind.PAYMENT -> ProposedLedgerKind.PRIOR_DEBT
                        ProposedLedgerKind.PRIOR_DEBT, null -> ProposedLedgerKind.SALE
                    }
                    onUpdateKind(nextKind)
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_receipt_long),
                    contentDescription = stringResource(R.string.ai_ledger_field_kind),
                    modifier = Modifier.size(20.dp),
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.ai_ledger_action_delete_row),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RowCropThumbnail(
    captureFile: File?,
    operation: LedgerOperation,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(captureFile, operation.region) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(captureFile, operation.region) { mutableStateOf(false) }

    LaunchedEffect(captureFile, operation.region) {
        if (captureFile != null && operation.region != null) {
            val decoded = withContext(Dispatchers.IO) {
                LedgerCropHelper.decodeCrop(captureFile, operation.region)
            }
            bitmap = decoded
        } else {
            bitmap = null
        }
        loaded = true
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.ai_ledger_crop_content_description),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = stringResource(R.string.ai_ledger_crop_not_available),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(2.dp),
            )
        }
    }
}

@Composable
private fun EditLedgerOperationDialog(
    operation: LedgerOperation,
    captureFile: File?,
    customerMatch: com.cuentivo.app.domain.EntityMatchResult<com.cuentivo.app.domain.CustomerCandidate>? = null,
    productMatch: com.cuentivo.app.domain.EntityMatchResult<ProductCandidate>? = null,
    duplicateMatch: com.cuentivo.app.domain.LedgerDuplicateMatch? = null,
    objections: List<com.cuentivo.app.domain.LedgerDeterministicObjection>,
    onAcceptCustomerAnchor: (com.cuentivo.app.domain.CustomerCandidate) -> Unit = {},
    onAcceptProductAnchor: (ProductCandidate) -> Unit = {},
    onConfirmObjection: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (customerName: String, amountCents: String, kind: ProposedLedgerKind, date: SafeDateCandidate?) -> Unit,
) {
    var name by remember { mutableStateOf(operation.customerName ?: "") }
    // In pesos, because that is what the notebook says and what this dialog exists to compare
    // against. Showing raw cents turned a misread "$999" into a field reading 9990, which is close
    // enough to the paper to be waved through.
    var amount by remember { mutableStateOf(LedgerAmountInput.toPesosText(operation.amountCents)) }
    val amountCents = LedgerAmountInput.toCents(amount)
    val amountIsWrong = amount.isNotBlank() && amountCents == null
    // Deliberately nullable: an operation whose kind Gemini could not read must be chosen by the
    // person, not defaulted to SALE. Defaulting would let a name correction silently classify a
    // line as a sale nobody picked.
    var kind by remember { mutableStateOf(operation.proposedKind) }
    var dateText by remember {
        mutableStateOf(
            operation.date?.let { String.format("%04d-%02d-%02d", it.year, it.month, it.day) } ?: "",
        )
    }
    val parsedDate = parseLedgerDateInput(dateText)
    val dateIsBlank = dateText.isBlank()
    val dateIsWrong = !dateIsBlank && parsedDate == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.ai_ledger_dialog_edit_title, operation.lineIndex))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Crop thumbnail inside dialog to facilitate visual check
                RowCropThumbnail(
                    captureFile = captureFile,
                    operation = operation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                )

                // Deterministic objections banner
                val rowObjections = objections.filter { it.lineIndex == operation.lineIndex }
                for (objection in rowObjections) {
                    val objectionMessage = when (objection.type) {
                        com.cuentivo.app.domain.LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT ->
                            stringResource(R.string.ai_ledger_objection_decimal_shift)
                        com.cuentivo.app.domain.LedgerDeterministicObjectionType.PRICE_MISMATCH_SUSPECT ->
                            stringResource(R.string.ai_ledger_objection_price_mismatch)
                        com.cuentivo.app.domain.LedgerDeterministicObjectionType.UNUSUAL_CENTS ->
                            stringResource(R.string.ai_ledger_objection_unusual_cents)
                        com.cuentivo.app.domain.LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED ->
                            stringResource(R.string.ai_ledger_objection_customer_confirmation)
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ledger-edit-objection-warning"),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = objectionMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
                if (rowObjections.isNotEmpty() && !com.cuentivo.app.domain.LedgerObjectionConfirmation.isConfirmed(operation)) {
                    Button(
                        onClick = onConfirmObjection,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ledger-edit-confirm-objection-button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_ledger_action_confirm_objection),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                if (duplicateMatch != null) {
                    val dateStr = runCatching {
                        java.time.LocalDate.ofEpochDay(duplicateMatch.effectiveDateEpochDay).toString()
                    }.getOrDefault("")
                    val descSuffix = if (!duplicateMatch.description.isNullOrBlank()) ": ${duplicateMatch.description}" else ""
                    val kindStr = when (duplicateMatch.kind) {
                        ProposedLedgerKind.SALE -> stringResource(R.string.ai_ledger_kind_sale)
                        ProposedLedgerKind.PAYMENT -> stringResource(R.string.ai_ledger_kind_payment)
                        ProposedLedgerKind.PRIOR_DEBT -> stringResource(R.string.ai_ledger_kind_prior_debt)
                    }
                    val detailText = stringResource(
                        R.string.ai_ledger_duplicate_detail_format,
                        kindStr.lowercase(),
                        duplicateMatch.customerName,
                        formatCents(duplicateMatch.amountCents),
                        dateStr,
                        descSuffix,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ledger-edit-duplicate-warning"),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_ledger_duplicate_warning, detailText),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ai_ledger_field_customer)) },
                    placeholder = { Text(stringResource(R.string.ai_ledger_field_customer_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // What the notebook says next to who the business already knows. Offered, not
                // applied: the name on the paper stays until somebody says these are the same
                // person, because that decision is what the money follows afterwards.
                val candidate = customerMatch?.candidate
                if (candidate != null) {
                    TextButton(
                        onClick = { onAcceptCustomerAnchor(candidate) },
                        modifier = Modifier.fillMaxWidth().testTag("ledger-accept-customer-match"),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_ledger_customer_match_action, candidate.name),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                // Catalog product offer: offered, never forced. Accepting only updates description,
                // leaving amount, price, productId, and inventory untouched.
                val prodCandidate = productMatch?.candidate
                if (prodCandidate != null) {
                    TextButton(
                        onClick = { onAcceptProductAnchor(prodCandidate) },
                        modifier = Modifier.fillMaxWidth().testTag("ledger-accept-product-match"),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_ledger_product_match_action, prodCandidate.name),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.ai_ledger_field_amount)) },
                    placeholder = { Text(stringResource(R.string.ai_ledger_field_amount_placeholder)) },
                    isError = amountIsWrong,
                    supportingText = if (amountIsWrong) {
                        { Text(stringResource(R.string.ai_ledger_field_amount_invalid)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ledger-edit-amount-field"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text(stringResource(R.string.ai_ledger_field_date)) },
                    placeholder = { Text(stringResource(R.string.ai_ledger_field_date_placeholder)) },
                    isError = dateIsWrong,
                    supportingText = if (dateIsWrong) {
                        { Text(stringResource(R.string.ai_ledger_field_date_invalid)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ledger-edit-date-field"),
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    KindChoiceButton(
                        text = stringResource(R.string.ai_ledger_kind_sale),
                        selected = kind == ProposedLedgerKind.SALE,
                        onClick = { kind = ProposedLedgerKind.SALE },
                        modifier = Modifier.weight(1f),
                    )
                    KindChoiceButton(
                        text = stringResource(R.string.ai_ledger_kind_payment),
                        selected = kind == ProposedLedgerKind.PAYMENT,
                        onClick = { kind = ProposedLedgerKind.PAYMENT },
                        modifier = Modifier.weight(1f),
                    )
                    KindChoiceButton(
                        text = stringResource(R.string.ai_ledger_kind_prior_debt),
                        selected = kind == ProposedLedgerKind.PRIOR_DEBT,
                        onClick = { kind = ProposedLedgerKind.PRIOR_DEBT },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            val chosenKind = kind
            TextButton(
                onClick = {
                    if (chosenKind != null && !dateIsWrong && amountCents != null) {
                        onSave(name, amountCents, chosenKind, parsedDate)
                    }
                },
                enabled = chosenKind != null && !dateIsWrong && amountCents != null,
                modifier = Modifier.testTag("ledger-edit-save-button"),
            ) {
                Text(stringResource(R.string.ai_ledger_action_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("ledger-edit-cancel-button"),
            ) {
                Text(stringResource(R.string.ai_ledger_action_cancel))
            }
        },
    )
}

@Composable
private fun KindChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Reads a date the person typed as YYYY-MM-DD. Returns null for anything that is not a real
 * calendar day, so the dialog can refuse to save it rather than storing a day that never existed.
 */
internal fun parseLedgerDateInput(text: String): SafeDateCandidate? {
    val parts = text.trim().split("-")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    return runCatching {
        java.time.LocalDate.of(year, month, day)
        SafeDateCandidate(year, month, day)
    }.getOrNull()
}

/**
 * One field, one colour, one mark. The colour is decided by [LedgerFieldHealth]; this only draws it.
 *
 * The mark is not only colour: a green chip and a yellow one have to be told apart by somebody who
 * cannot distinguish them, and by somebody looking at a phone in the sun on a market stall.
 */
@Composable
private fun FieldHealthChip(
    label: String,
    color: com.cuentivo.app.domain.FieldHealthColor,
    inherited: Boolean = false,
) {
    val background = when (color) {
        com.cuentivo.app.domain.FieldHealthColor.GREEN -> MaterialTheme.colorScheme.secondaryContainer
        com.cuentivo.app.domain.FieldHealthColor.YELLOW -> MaterialTheme.colorScheme.tertiaryContainer
        com.cuentivo.app.domain.FieldHealthColor.RED -> MaterialTheme.colorScheme.errorContainer
    }
    val foreground = when (color) {
        com.cuentivo.app.domain.FieldHealthColor.GREEN -> MaterialTheme.colorScheme.onSecondaryContainer
        com.cuentivo.app.domain.FieldHealthColor.YELLOW -> MaterialTheme.colorScheme.onTertiaryContainer
        com.cuentivo.app.domain.FieldHealthColor.RED -> MaterialTheme.colorScheme.onErrorContainer
    }
    val mark = when {
        // An inherited value is green in every other sense and still worth confirming, so it gets a
        // mark of its own rather than being folded into the same "?" as an unclear reading.
        inherited -> "↑"
        color == com.cuentivo.app.domain.FieldHealthColor.GREEN -> "OK"
        color == com.cuentivo.app.domain.FieldHealthColor.YELLOW -> "?"
        else -> "!"
    }
    Surface(color = background, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = mark + " " + label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private data class FieldChip(
    val label: String,
    val color: com.cuentivo.app.domain.FieldHealthColor,
    val inherited: Boolean,
)

/**
 * A chip only for a field that needs something, and null for one that does not.
 *
 * Green means "nothing to do here", and a badge that says nothing to do is a badge that teaches
 * people to stop reading badges. It also costs width, which on a row of four is what pushed the
 * card past the height of the screen.
 */
private fun chipFor(
    label: String,
    color: com.cuentivo.app.domain.FieldHealthColor,
    origin: com.cuentivo.app.domain.FieldOrigin,
): FieldChip? {
    val inherited = origin != com.cuentivo.app.domain.FieldOrigin.READ_HERE
    if (color == com.cuentivo.app.domain.FieldHealthColor.GREEN && !inherited) return null
    return FieldChip(label = label, color = color, inherited = inherited)
}
