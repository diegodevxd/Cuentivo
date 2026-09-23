package com.cuentivo.app.ui.customer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.CustomerScore
import com.cuentivo.app.domain.CustomerStatementSummary
import com.cuentivo.app.domain.PaymentReliability
import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.ui.CorrectionCandidate
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoEmptyState
import com.cuentivo.app.ui.NeoMoney
import com.cuentivo.app.ui.NeoSectionHeader
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MOVEMENT_ROW_HEIGHT = 60.dp
private val MOVEMENT_ROW_HEIGHT_WITH_BALANCE = 72.dp

/**
 * The scrollable itself needs a handle: on a short screen the corrections live below the fold and
 * a lazy list has not composed them yet, so a test has to scroll the list rather than reach for a
 * node that does not exist.
 */
const val DETAIL_LIST_TAG = "customer-detail-list"

/**
 * The customer's account, faithful to `design/stitch/cuentivo-customer-detail.png`.
 *
 * It is called a sheet for continuity with the callbacks it replaces, but it renders as a full
 * task inside the shell: the brand header steps aside, this screen supplies its own back arrow and
 * centred title, and the bottom bar stays put — exactly what the reference shows.
 *
 * Nothing here recomputes money. The balance is the ledger-derived `CustomerBalanceRow`, the rows
 * are `StatementEntry` values as loaded, and cancelling still writes a compensating movement
 * through the callbacks it was given.
 */
@Composable
fun CustomerDetailSheet(
    customer: CustomerBalanceRow,
    entries: List<StatementEntry>,
    cancelables: List<CorrectionCandidate>,
    modifier: Modifier = Modifier,
    dueSales: List<DueSale> = emptyList(),
    /** Null until the scores load, or when this customer has none. The line is simply absent. */
    score: CustomerScore? = null,
    onRegisterPayment: () -> Unit = {},
    onCallCustomer: (String) -> Unit = {},
    onCancelSale: (CorrectionCandidate.Sale) -> Unit = {},
    /** Opens the sale form on an already registered sale. The host replaces it, never edits it. */
    onEditSale: (CorrectionCandidate.Sale) -> Unit = {},
    onCancelPayment: (CorrectionCandidate.Payment) -> Unit = {},
    onShareCsv: () -> Unit = {},
    onShareText: () -> Unit = {},
    onDismiss: () -> Unit = {},
    /** Non-null only when CreditLimitAdvisor has something to offer and the owner hasn't dismissed it. */
    suggestedCreditLimitCents: Long? = null,
    onAcceptCreditLimitSuggestion: (Long) -> Unit = {},
    onDismissCreditLimitSuggestion: (Long) -> Unit = {},
) {
    var pendingCancel by remember { mutableStateOf<CorrectionCandidate?>(null) }
    var correctionsExpanded by remember { mutableStateOf(false) }
    var shareMenuOpen by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)

    val today = LocalDate.now().toEpochDay()
    val status = CustomerDirectoryPresentation.status(customer, dueSales, today)
    val shareSummaryLabel = stringResource(R.string.detail_share_summary)
    val shareCsvLabel = stringResource(R.string.detail_share_csv)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
        LazyColumn(
            modifier = Modifier
                .widthIn(max = layout.contentMaxWidthDp.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .testTag(DETAIL_LIST_TAG),
            contentPadding = PaddingValues(
                start = layout.horizontalPaddingDp.dp,
                end = layout.horizontalPaddingDp.dp,
                bottom = NeoSpacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.md),
        ) {
            item { DetailTopBar(onDismiss) }
            item { BalanceHero(customer.balanceCents, status, score) }
            if (entries.isNotEmpty()) {
                item { TotalsRow(CustomerStatementSummary.totals(entries), customer.balanceCents) }
            }
            customer.customer.creditLimitCents?.let { creditLimitCents ->
                item { CreditLimitRow(creditLimitCents, customer.balanceCents) }
            }
            suggestedCreditLimitCents?.let { suggested ->
                item {
                    CreditLimitSuggestionCard(
                        customerName = customer.customer.name,
                        suggestedCents = suggested,
                        onAccept = { onAcceptCreditLimitSuggestion(suggested) },
                        onDismiss = { onDismissCreditLimitSuggestion(suggested) },
                    )
                }
            }
            item { IdentityCard(customer, onCallCustomer) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm)) {
                    NeoAction(stringResource(R.string.detail_register_payment), onRegisterPayment, Modifier.weight(1f))
                    Box(Modifier.weight(1f)) {
                        NeoAction(
                            label = stringResource(R.string.detail_share),
                            onClick = { shareMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            primary = false,
                        )
                        DropdownMenu(
                            expanded = shareMenuOpen,
                            onDismissRequest = { shareMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(shareSummaryLabel) },
                                onClick = { shareMenuOpen = false; onShareText() },
                            )
                            DropdownMenuItem(
                                text = { Text(shareCsvLabel) },
                                onClick = { shareMenuOpen = false; onShareCsv() },
                            )
                        }
                    }
                }
            }
            item { NeoSectionHeader(title = stringResource(R.string.detail_history)) }

            if (entries.isEmpty()) {
                item {
                    NeoEmptyState(
                        title = stringResource(R.string.detail_no_movements_title),
                        detail = stringResource(R.string.detail_no_movements_detail),
                    )
                }
            } else {
                // MainActivity keeps entries chronological so the CSV and the running balance stay
                // correct. Only the display is reversed, so the newest movement reads first.
                val runningBalances = CustomerStatementSummary.runningBalances(entries)
                val canceledPaymentIds = CustomerStatementSummary.canceledPaymentIds(entries)
                val rows = entries.zip(runningBalances).asReversed()
                items(rows) { (entry, balanceAfter) ->
                    val isCanceledAbono = entry.type == "PAYMENT" && entry.paymentId in canceledPaymentIds
                    MovementRow(entry, balanceAfter, isCanceledAbono)
                }
            }

            if (cancelables.isNotEmpty()) {
                item {
                    NeoSectionHeader(
                        title = stringResource(R.string.detail_correct),
                        trailing = {
                            TextButton(onClick = { correctionsExpanded = !correctionsExpanded }) {
                                Text(
                                    stringResource(
                                        if (correctionsExpanded) R.string.detail_corrections_hide
                                        else R.string.detail_corrections_show,
                                    ),
                                )
                            }
                        },
                    )
                }
                if (correctionsExpanded) {
                    item {
                        Text(
                            stringResource(R.string.detail_correct_note),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(cancelables) { item ->
                        CorrectionRow(item, onEdit = onEditSale) { pendingCancel = item }
                    }
                }
            }
        }
    }

    pendingCancel?.let { item ->
        val isSale = item is CorrectionCandidate.Sale
        val name = customer.customer.name
        val dialogTitle = stringResource(if (isSale) R.string.detail_cancel_sale_title else R.string.detail_cancel_payment_title)
        val dialogDetail = if (isSale) {
            val sale = item as CorrectionCandidate.Sale
            if (sale.activePaidCents > 0L) stringResource(R.string.detail_cancel_sale_partial_detail, sale.saleNumber, Money.format(sale.totalCents), Money.format(sale.activePaidCents), Money.format(sale.outstandingCents))
            else stringResource(R.string.detail_cancel_sale_detail, sale.saleNumber, Money.format(sale.outstandingCents), name)
        } else {
            val payment = item as CorrectionCandidate.Payment
            stringResource(R.string.detail_cancel_payment_detail, stringResource(R.string.detail_payment_ordinal, payment.ordinal, payment.groupSize), name, Money.format(payment.amountCents))
        }
        val confirmLabel = stringResource(R.string.detail_cancel_confirm)
        val dismissLabel = stringResource(R.string.detail_cancel_dismiss)
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text(dialogTitle) },
            text = { Text(dialogDetail) },
            confirmButton = {
                TextButton(onClick = {
                    if (isSale) onCancelSale(item as CorrectionCandidate.Sale)
                    else onCancelPayment(item as CorrectionCandidate.Payment)
                    pendingCancel = null
                }) { Text(confirmLabel) }
            },
            dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text(dismissLabel) } },
        )
    }
}

@Composable
private fun DetailTopBar(onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterStart).size(48.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.detail_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.detail_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BalanceHero(balanceCents: Long, status: CustomerStatus, score: CustomerScore?) {
    val presentation = BalancePresenter.from(balanceCents)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NeoShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            // Horizontal padding, not just vertical: the score line is a long sentence and, with
            // the card edge as its only bound, it ran flush against the border (v44).
            modifier = Modifier.fillMaxWidth().padding(horizontal = NeoSpacing.lg, vertical = NeoSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.detail_balance_total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(NeoSpacing.xs))
            NeoMoney(
                amountCents = presentation.amountCents,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(NeoSpacing.sm))
            Text(
                text = stringResource(
                    when (presentation.kind) {
                        BalanceKind.OWED -> R.string.detail_balance_pending
                        BalanceKind.SETTLED -> R.string.detail_balance_settled
                        BalanceKind.CUSTOMER_CREDIT -> R.string.detail_balance_credit
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.addsToBalanceKind()) {
                Spacer(Modifier.height(NeoSpacing.xs))
                StatusPill(status)
            }
            ScoreLine(score)
        }
    }
}

/**
 * The score in words, under the balance: how this customer pays, and on how much evidence.
 *
 * The sample size is spelled out on purpose. "Pays on time" over three sales and over forty are
 * very different claims, and hiding which one you are looking at is how a number stops being
 * trustworthy.
 */
@Composable
private fun ScoreLine(score: CustomerScore?) {
    if (score == null) return
    val headline = score.reliability.labelRes()?.let { stringResource(it) }
        ?: stringResource(R.string.customer_score_no_history)
    val detail = when {
        score.reliability == PaymentReliability.NO_HISTORY -> null
        score.averageDaysLate <= 0 -> stringResource(R.string.customer_score_on_time)
        else -> stringResource(R.string.customer_score_average_days, score.averageDaysLate)
    }
    Spacer(Modifier.height(NeoSpacing.xs))
    Text(
        text = listOfNotNull(
            headline,
            detail,
            score.ratedSaleCount
                .takeIf { it > 0 }
                ?.let { pluralStringResource(R.plurals.customer_score_rated_sales, it, it) },
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun StatusPill(status: CustomerStatus) {
    val accent = when (status.tone) {
        CustomerTone.OVERDUE -> MaterialTheme.colorScheme.error
        CustomerTone.SETTLED -> MaterialTheme.colorScheme.secondary
        CustomerTone.CREDIT -> MaterialTheme.colorScheme.primary
        CustomerTone.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .background(accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = NeoSpacing.sm, vertical = NeoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.tone == CustomerTone.OVERDUE) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(NeoSpacing.xs))
        }
        Text(statusLabel(status), style = MaterialTheme.typography.labelSmall, color = accent)
    }
}

@Composable
private fun statusLabel(status: CustomerStatus): String = when (status.tone) {
    CustomerTone.SETTLED -> stringResource(R.string.customer_status_settled)
    CustomerTone.CREDIT -> stringResource(R.string.customer_status_credit)
    CustomerTone.PENDING -> status.daysUntilDue?.let { days ->
        if (days == 0L) stringResource(R.string.customer_status_due_today)
        else stringResource(R.string.customer_status_due_in_days, days)
    } ?: stringResource(R.string.customer_status_pending)
    CustomerTone.OVERDUE -> stringResource(
        if (status.overdueDays == 1L) R.string.customer_status_overdue_one
        else R.string.customer_status_overdue_days,
        status.overdueDays ?: 0L,
    )
}

@Composable
private fun paymentBusinessDate(paymentDateEpochDay: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM uuuu", locale) }
    return LocalDate.ofEpochDay(paymentDateEpochDay).format(formatter)
}

@Composable
private fun paymentRegistrationTime(createdAtEpochMillis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("HH:mm", locale) }
    return Instant.ofEpochMilli(createdAtEpochMillis).atZone(ZoneId.systemDefault()).format(formatter)
}

@Composable
private fun paymentMethodLabel(method: String): String = when (method.uppercase()) {
    "CASH" -> stringResource(R.string.payment_method_cash)
    "TRANSFER" -> stringResource(R.string.payment_method_transfer)
    "CARD" -> stringResource(R.string.payment_method_card)
    else -> method.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
}

/**
 * The three numbers testers said the screen was missing. `totals.balanceCents` must always match
 * `customer.balanceCents` above — both come from the same DEBIT-minus-CREDIT ledger arithmetic, one
 * in SQL (`FinancialDao.customerBalanceCents`) and one here (`CustomerStatementSummary.totals`).
 */
@Composable
private fun TotalsRow(totals: CustomerStatementSummary.Totals, balanceCents: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
    ) {
        TotalsCell(stringResource(R.string.detail_sold_to_them), totals.totalChargedCents, Modifier.weight(1f))
        TotalsCell(stringResource(R.string.detail_they_paid), totals.totalPaidCents, Modifier.weight(1f))
        TotalsCell(
            label = stringResource(
                when (BalancePresenter.from(balanceCents).kind) {
                    BalanceKind.OWED -> R.string.detail_owes_today
                    BalanceKind.SETTLED -> R.string.detail_balance_settled
                    BalanceKind.CUSTOMER_CREDIT -> R.string.detail_balance_credit
                },
            ),
            amountCents = balanceCents,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TotalsCell(label: String, amountCents: Long, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        NeoMoney(
            amountCents = BalancePresenter.from(amountCents).amountCents,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun CreditLimitSuggestionCard(
    customerName: String,
    suggestedCents: Long,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NeoShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(NeoSpacing.md)) {
            Text(
                text = stringResource(R.string.detail_credit_suggestion, customerName, Money.format(suggestedCents)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(NeoSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm)) {
                NeoAction(label = stringResource(R.string.detail_credit_raise), onClick = onAccept, modifier = Modifier.weight(1f))
                NeoAction(
                    label = stringResource(R.string.detail_credit_not_now),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    primary = false,
                )
            }
        }
    }
}

/** Only rendered when the customer has a credit limit set — most don't, and null means unlimited. */
@Composable
private fun CreditLimitRow(creditLimitCents: Long, balanceCents: Long) {
    val availableCents = (creditLimitCents - balanceCents).coerceAtLeast(0L)
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.detail_credit_line, Money.format(creditLimitCents), Money.format(availableCents)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Reads the composition's own locale instead of a fixed one, so switching the app to English also
 * switches how dates read here — otherwise "Idioma" would flip every label but leave every date
 * saying "2 ago 2026" underneath.
 */
@Composable
private fun displayDate(dateIso: String): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM uuuu", locale) }
    return LocalDate.parse(dateIso).format(formatter)
}

@Composable
private fun IdentityCard(customer: CustomerBalanceRow, onCall: (String) -> Unit) {
    val phone = customer.customer.phone
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NeoShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NeoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, NeoShapes.row),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = CustomerDirectoryPresentation.initials(customer.customer.name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = NeoSpacing.md)) {
                Text(
                    text = customer.customer.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = phone ?: customer.customer.email ?: stringResource(R.string.detail_no_contact),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Offering a call button without a number would be a dead control.
            if (phone != null) {
                IconButton(
                    onClick = { onCall(phone) },
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, NeoShapes.row),
                ) {
                    Icon(
                        Icons.Filled.Phone,
                        contentDescription = stringResource(R.string.detail_call, customer.customer.name),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MovementRow(entry: StatementEntry, balanceAfterCents: Long, isCanceled: Boolean) {
    val visual = CustomerLedgerPresentation.from(entry.type, entry.direction, entry.isPriorDebt)
    val accent = when (visual.tone) {
        LedgerTone.DEBT -> MaterialTheme.colorScheme.error
        LedgerTone.CREDIT -> MaterialTheme.colorScheme.secondary
    }
    Card(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = MOVEMENT_ROW_HEIGHT_WITH_BALANCE),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NeoSpacing.md, vertical = NeoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, NeoShapes.row),
                contentAlignment = Alignment.Center,
            ) {
                // material-icons-core only: a cart stands in for a sale, a list for a debt written
                // in the notebook before the app existed, and a tick for anything that pays down.
                Icon(
                    imageVector = when {
                        visual.isPriorDebt -> Icons.AutoMirrored.Filled.List
                        visual.tone == LedgerTone.DEBT -> Icons.Filled.ShoppingCart
                        else -> Icons.Filled.Check
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = NeoSpacing.md)) {
                Text(
                    text = if (visual.isPriorDebt) {
                        stringResource(R.string.detail_movement_prior_debt)
                    } else {
                        visual.label
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDecoration = if (isCanceled) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = displayDate(entry.dateIso),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // What was sold. Without it a history of four "Venta" rows is four identical rows.
                entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = visual.sign + Money.format(entry.amountCents),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (isCanceled) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                    color = accent,
                    maxLines = 1,
                )
                Text(
                    text = if (isCanceled) stringResource(R.string.detail_canceled) else visual.caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when (BalancePresenter.from(balanceAfterCents).kind) {
                        BalanceKind.OWED -> stringResource(R.string.detail_balance_after, Money.format(BalancePresenter.from(balanceAfterCents).amountCents))
                        BalanceKind.SETTLED -> stringResource(R.string.detail_balance_after_settled)
                        BalanceKind.CUSTOMER_CREDIT -> stringResource(R.string.detail_balance_after_credit, Money.format(BalancePresenter.from(balanceAfterCents).amountCents))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CorrectionRow(
    item: CorrectionCandidate,
    onEdit: (CorrectionCandidate.Sale) -> Unit,
    onCancel: () -> Unit,
) {
    val actionDescription = (item as? CorrectionCandidate.Payment)?.let {
        stringResource(
            R.string.detail_payment_cancel_action_description,
            it.ordinal,
            Money.format(it.amountCents),
            paymentBusinessDate(it.paymentDateEpochDay),
            paymentRegistrationTime(it.createdAtEpochMillis),
            paymentMethodLabel(it.paymentMethod),
        )
    }
    val cancelLabel = stringResource(R.string.detail_cancel)
    Card(
        modifier = Modifier.fillMaxWidth().then(
            // Only payments keep the whole-card shortcut: a sale row offers Editar *and* Cancelar,
            // and a card that silently means one of them is a trap.
            if (item !is CorrectionCandidate.Payment || item.blockedByCanceledSale) Modifier
            else Modifier.clickable(onClick = onCancel),
        ),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = NeoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = NeoSpacing.sm)) {
                when (item) {
                    is CorrectionCandidate.Sale -> {
                        Text(stringResource(R.string.detail_correction_sale), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            stringResource(
                                R.string.detail_sale_correction_amounts,
                                Money.format(item.totalCents),
                                Money.format(item.activePaidCents),
                                Money.format(item.outstandingCents),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is CorrectionCandidate.Payment -> {
                        Text(
                            stringResource(R.string.detail_payment_ordinal, item.ordinal, item.groupSize),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                R.string.detail_payment_business_date,
                                paymentBusinessDate(item.paymentDateEpochDay),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(
                                R.string.detail_payment_registered_time,
                                paymentRegistrationTime(item.createdAtEpochMillis),
                                paymentMethodLabel(item.paymentMethod),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.reference?.let { reference ->
                            Text(
                                stringResource(R.string.detail_payment_reference, reference),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (item.blockedByCanceledSale) {
                            Text(
                                stringResource(R.string.detail_payment_cancel_blocked),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if (item is CorrectionCandidate.Sale) {
                val editDescription = stringResource(R.string.detail_sale_edit_description, item.saleNumber)
                TextButton(onClick = { onEdit(item) }) {
                    Text(
                        stringResource(R.string.detail_sale_edit),
                        modifier = Modifier.semantics { contentDescription = editDescription },
                    )
                }
            }
            if (item !is CorrectionCandidate.Payment || !item.blockedByCanceledSale) {
                TextButton(onClick = onCancel) {
                    Text(
                        cancelLabel,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics {
                            contentDescription = actionDescription ?: cancelLabel
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactRow(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = MOVEMENT_ROW_HEIGHT),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = NeoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
