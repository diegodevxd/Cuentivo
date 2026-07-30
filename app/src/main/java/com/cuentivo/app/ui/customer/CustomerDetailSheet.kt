package com.cuentivo.app.ui.customer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.ui.CancelableItem
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoMoney
import com.cuentivo.app.ui.NeoSectionHeader
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing
import java.time.LocalDate

private val MOVEMENT_ROW_HEIGHT = 60.dp

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
    cancelables: List<CancelableItem>,
    modifier: Modifier = Modifier,
    dueSales: List<DueSale> = emptyList(),
    onNewSale: () -> Unit = {},
    onRegisterPayment: () -> Unit = {},
    onCancelSale: (CancelableItem) -> Unit = {},
    onCancelPayment: (CancelableItem) -> Unit = {},
    onShareCsv: () -> Unit = {},
    onShareText: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    var pendingCancel by remember { mutableStateOf<CancelableItem?>(null) }
    var shareMenuOpen by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)

    val today = LocalDate.now().toEpochDay()
    val status = CustomerDirectoryPresentation.status(customer, dueSales, today)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
        LazyColumn(
            modifier = Modifier
                .widthIn(max = layout.contentMaxWidthDp.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(
                start = layout.horizontalPaddingDp.dp,
                end = layout.horizontalPaddingDp.dp,
                bottom = NeoSpacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.md),
        ) {
            item { DetailTopBar(onDismiss) }
            item { BalanceHero(customer.balanceCents, status) }
            item { IdentityCard(customer) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm)) {
                    NeoAction("Registrar abono", onRegisterPayment, Modifier.weight(1f))
                    Box(Modifier.weight(1f)) {
                        NeoAction(
                            label = "Compartir",
                            onClick = { shareMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            primary = false,
                        )
                        DropdownMenu(
                            expanded = shareMenuOpen,
                            onDismissRequest = { shareMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Compartir resumen") },
                                onClick = { shareMenuOpen = false; onShareText() },
                            )
                            DropdownMenuItem(
                                text = { Text("Compartir CSV") },
                                onClick = { shareMenuOpen = false; onShareCsv() },
                            )
                        }
                    }
                }
            }
            item {
                // Tagged because the bottom bar's central action carries the same words; without a
                // stable handle a test can end up driving the wrong one.
                NeoAction(
                    label = "Nueva venta",
                    onClick = onNewSale,
                    modifier = Modifier.fillMaxWidth().testTag("detail-new-sale"),
                    primary = false,
                )
            }
            item { NeoSectionHeader(title = "Historial de Movimientos") }

            if (entries.isEmpty()) {
                item { CompactRow("Este cliente aún no tiene movimientos") }
            } else {
                // MainActivity keeps entries chronological so the CSV and the running balance stay
                // correct. Only the display is reversed, so the newest movement reads first.
                items(entries.asReversed()) { entry -> MovementRow(entry) }
            }

            if (cancelables.isNotEmpty()) {
                item { NeoSectionHeader(title = "Corregir") }
                item {
                    Text(
                        "Nada se borra: se registra un movimiento que compensa el error y queda en el historial.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(cancelables) { item -> CorrectionRow(item) { pendingCancel = item } }
            }
        }
    }

    pendingCancel?.let { item ->
        val isSale = item.kind == CancelableItem.Kind.SALE
        val name = customer.customer.name
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text(if (isSale) "¿Cancelar esta venta?" else "¿Cancelar este abono?") },
            text = {
                Text(
                    if (isSale) {
                        "${item.title} — se le quitarán ${Money.format(item.amountCents)} de deuda a $name. " +
                            "La venta no se borra: queda cancelada y con su movimiento de corrección."
                    } else {
                        "${item.title} — la deuda de $name subirá ${Money.format(item.amountCents)}, " +
                            "porque el abono deja de contar. El cobro original queda registrado."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isSale) onCancelSale(item) else onCancelPayment(item)
                    pendingCancel = null
                }) { Text("Sí, cancelar") }
            },
            dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text("No") } },
        )
    }
}

@Composable
private fun DetailTopBar(onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterStart).size(48.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "Detalle de Cliente",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BalanceHero(balanceCents: Long, status: CustomerStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NeoShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = NeoSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "BALANCE TOTAL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(NeoSpacing.xs))
            NeoMoney(
                amountCents = balanceCents,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(NeoSpacing.sm))
            StatusPill(status)
        }
    }
}

@Composable
private fun StatusPill(status: CustomerStatus) {
    val accent = when (status.tone) {
        CustomerTone.OVERDUE -> MaterialTheme.colorScheme.error
        CustomerTone.SETTLED -> MaterialTheme.colorScheme.secondary
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
        Text(status.label, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}

@Composable
private fun IdentityCard(customer: CustomerBalanceRow) {
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
                    text = phone ?: customer.customer.email ?: "Sin contacto registrado",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Offering a call button without a number would be a dead control.
            if (phone != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, NeoShapes.row),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Phone,
                        contentDescription = "Teléfono de ${customer.customer.name}",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MovementRow(entry: StatementEntry) {
    val visual = CustomerLedgerPresentation.from(entry.type, entry.direction)
    val accent = when (visual.tone) {
        LedgerTone.DEBT -> MaterialTheme.colorScheme.error
        LedgerTone.CREDIT -> MaterialTheme.colorScheme.secondary
    }
    Card(
        modifier = Modifier.fillMaxWidth().height(MOVEMENT_ROW_HEIGHT),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = NeoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, NeoShapes.row),
                contentAlignment = Alignment.Center,
            ) {
                // material-icons-core only: a cart stands in for anything that grows the debt, a
                // tick for anything that pays it down.
                Icon(
                    imageVector = if (visual.tone == LedgerTone.DEBT) {
                        Icons.Filled.ShoppingCart
                    } else {
                        Icons.Filled.Check
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = NeoSpacing.md)) {
                Text(
                    text = visual.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.dateIso,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = visual.sign + Money.format(entry.amountCents),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = accent,
                    maxLines = 1,
                )
                Text(
                    text = visual.caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CorrectionRow(item: CancelableItem, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = NeoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = NeoSpacing.sm)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onCancel) {
                Text("Cancelar", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CompactRow(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth().height(MOVEMENT_ROW_HEIGHT),
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
