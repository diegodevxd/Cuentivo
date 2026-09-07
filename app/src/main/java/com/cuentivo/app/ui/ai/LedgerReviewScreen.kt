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
    val rowsNeedingReview = review.operations.filterNot { isRegisterableLedgerOperation(it) }

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
            modifier = Modifier.fillMaxWidth(),
        )

        // Page date selector: shown ONLY when at least one registrable operation lacks a date
        if (hasRowsWithoutDate) {
            PageDateCard(
                pageDateEpochDay = review.pageDateEpochDay,
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
            review.summary.hasMissingDates || rowsNeedingReview.isNotEmpty()
        ) {
            FinancialErrorBanner(
                diagnostics = review.summary.financialDiagnostics,
                hasZeroOps = review.summary.executableOperationsCount == 0,
                hasMissingDates = review.summary.hasMissingDates,
                rowsNeedingReviewCount = rowsNeedingReview.size,
                onStartReview = {
                    pendingReviewQueue = rowsNeedingReview.map { it.lineIndex }.drop(1)
                    editingOperation = rowsNeedingReview.firstOrNull()
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
            items(review.customerGroups) { group ->
                CustomerGroupCard(
                    group = group,
                    captureFile = captureFile,
                    onEditOperation = { op -> editingOperation = op },
                    onUpdateKind = onUpdateKind,
                    onRemoveRow = onRemoveRow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Single confirmation button at the bottom
        val canConfirm = review.summary.canConfirm
        Button(
            onClick = onConfirm,
            enabled = canConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ledger-review-confirm-button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = stringResource(
                    R.string.ai_ledger_summary_action_format,
                    review.summary.newCustomersCount,
                    review.summary.executableOperationsCount,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    // Edit modal dialog when a row or unresolved field is tapped
    editingOperation?.let { op ->
        EditLedgerOperationDialog(
            operation = op,
            captureFile = captureFile,
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
    modifier: Modifier = Modifier,
) {
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
            Text(
                text = stringResource(R.string.ai_ledger_review_summary_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.ai_ledger_summary_sales_count, review.summary.salesCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.ai_ledger_summary_payments_count, review.summary.paymentsCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.ai_ledger_summary_prior_debts_count, review.summary.priorDebtsCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.ai_ledger_summary_visible_customers_count, review.summary.visibleCustomersCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.ai_ledger_summary_new_customers_count, review.summary.newCustomersCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (review.summary.excludedRowsCount > 0) {
                    Text(
                        text = stringResource(R.string.ai_ledger_summary_excluded_count, review.summary.excludedRowsCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun PageDateCard(
    pageDateEpochDay: Long?,
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
        }
    }
}

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
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Thumbnail Crop or "Sin recorte"
            RowCropThumbnail(
                captureFile = captureFile,
                operation = operation,
                modifier = Modifier.size(56.dp),
            )

            // Details
            Column(modifier = Modifier.weight(1f)) {
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
                }

                val amountCentsVal = operation.amountCents?.toLongOrNull()
                val amountText = if (amountCentsVal != null) {
                    formatCents(amountCentsVal)
                } else {
                    operation.amountCents ?: stringResource(R.string.ai_slot_amount)
                }
                Text(
                    text = amountText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )

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
                )

                if (operation.customerName != null) {
                    Text(
                        text = operation.customerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ai_ledger_field_customer)) },
                    placeholder = { Text(stringResource(R.string.ai_ledger_field_customer_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

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
