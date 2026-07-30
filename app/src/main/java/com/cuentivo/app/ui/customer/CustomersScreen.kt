package com.cuentivo.app.ui.customer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.Money
import com.cuentivo.app.ui.FormDialog
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoMoney
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing
import java.time.LocalDate

/** Matches the reference's three-line row: name, status, balance. */
private val DIRECTORY_ROW_HEIGHT = 72.dp

/**
 * The compact customer directory from `design/stitch/cuentivo-customers.png`.
 *
 * Every figure is read, never derived: balances arrive as `Long` cents from the ledger-backed
 * `CustomerBalanceRow`, and the status line comes from the same `dueSales` the dashboard uses.
 * Edit and archive move into a per-row menu so the list keeps the reference's density without
 * losing either action.
 */
@Composable
fun CustomersScreen(
    customers: List<CustomerBalanceRow>,
    dueSales: List<DueSale>,
    modifier: Modifier = Modifier,
    showForm: Boolean = false,
    onShowFormChange: (Boolean) -> Unit = {},
    onCreateCustomer: (String, String?) -> Unit = { _, _ -> },
    onEditCustomer: (CustomerBalanceRow, String, String?) -> Unit = { _, _, _ -> },
    onArchiveCustomer: (CustomerBalanceRow) -> Unit = {},
    onOpen: (CustomerBalanceRow) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var chip by remember { mutableStateOf(CustomerFilter.ALL) }
    var editing by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    var archiving by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    val today = LocalDate.now().toEpochDay()
    val visible = CustomerDirectoryPresentation.filter(customers, query, chip, dueSales, today)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
        LazyColumn(
            modifier = Modifier
                .widthIn(max = layout.contentMaxWidthDp.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(
                start = layout.horizontalPaddingDp.dp,
                top = NeoSpacing.md,
                end = layout.horizontalPaddingDp.dp,
                bottom = 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
        ) {
            item { Text("Cartera", style = MaterialTheme.typography.titleLarge) }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("Buscar cliente...", style = MaterialTheme.typography.bodyLarge)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    shape = NeoShapes.row,
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm)) {
                    CustomerFilter.entries.forEach { option ->
                        FilterChipRow(option.label, option == chip) { chip = option }
                    }
                }
            }
            if (visible.isEmpty()) {
                item { EmptyDirectoryRow(query.isBlank() && chip == CustomerFilter.ALL) }
            } else {
                items(visible, key = { it.customer.id }) { row ->
                    CustomerRow(
                        row = row,
                        status = CustomerDirectoryPresentation.status(row, dueSales, today),
                        onOpen = { onOpen(row) },
                        onEdit = { editing = row },
                        onArchive = { archiving = row },
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { onShowFormChange(true) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = layout.horizontalPaddingDp.dp, bottom = NeoSpacing.lg)
                .size(48.dp),
            shape = NeoShapes.card,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Nuevo cliente")
        }
    }

    if (showForm) {
        CustomerFormDialog(
            title = "Nuevo cliente",
            initialName = "",
            initialPhone = "",
            onConfirm = { name, phone ->
                onCreateCustomer(name, phone)
                onShowFormChange(false)
            },
            onDismiss = { onShowFormChange(false) },
        )
    }
    editing?.let { row ->
        CustomerFormDialog(
            title = "Editar cliente",
            initialName = row.customer.name,
            initialPhone = row.customer.phone.orEmpty(),
            formKey = row.customer.id,
            note = "Corregir el nombre no cambia sus ventas ni su saldo.",
            onConfirm = { name, phone ->
                onEditCustomer(row, name, phone)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    archiving?.let { row ->
        // Balance comes from the ledger, so this is the same number Inicio adds up. Archiving
        // someone who still owes would quietly shrink "Por cobrar" with nobody having paid.
        val settled = row.balanceCents == 0L
        AlertDialog(
            onDismissRequest = { archiving = null },
            title = {
                Text(
                    if (settled) {
                        "¿Eliminar a ${row.customer.name}?"
                    } else {
                        "${row.customer.name} tiene saldo"
                    }
                )
            },
            text = {
                Text(
                    if (settled) {
                        "Dejará de aparecer en tus listas. Su historial de ventas y abonos se conserva."
                    } else {
                        "No se puede eliminar mientras su saldo no esté en cero: hoy es " +
                            "${Money.format(row.balanceCents)}. Cobra lo que falta o cancela sus " +
                            "ventas desde su estado de cuenta, y vuelve a intentarlo."
                    }
                )
            },
            confirmButton = {
                if (settled) {
                    TextButton(onClick = { archiving = null; onArchiveCustomer(row) }) {
                        Text("Sí, eliminar", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = { archiving = null; onOpen(row) }) {
                        Text("Ver estado de cuenta")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { archiving = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun CustomerFormDialog(
    title: String,
    initialName: String,
    initialPhone: String,
    onConfirm: (String, String?) -> Unit,
    onDismiss: () -> Unit,
    formKey: Any? = Unit,
    note: String? = null,
) {
    // Keyed on the customer so reopening for another row starts from that row's data.
    var name by remember(formKey) { mutableStateOf(initialName) }
    var phone by remember(formKey) { mutableStateOf(initialPhone) }
    FormDialog(
        title = title,
        confirmEnabled = name.isNotBlank(),
        onConfirm = { if (name.isNotBlank()) onConfirm(name.trim(), phone.trim().ifBlank { null }) },
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nombre del cliente") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { raw -> phone = raw.filter { it.isDigit() || it == '+' }.take(15) },
            label = { Text("Teléfono (opcional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun FilterChipRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NeoSpacing.md, vertical = NeoSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun EmptyDirectoryRow(pristine: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = NeoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (pristine) "Aún no tienes clientes" else "Ningún cliente coincide",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CustomerRow(
    row: CustomerBalanceRow,
    status: CustomerStatus,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = when (status.tone) {
        CustomerTone.OVERDUE -> MaterialTheme.colorScheme.error
        CustomerTone.SETTLED -> MaterialTheme.colorScheme.secondary
        CustomerTone.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(DIRECTORY_ROW_HEIGHT)
            .clickable(onClick = onOpen),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // The reference marks a late customer with a red edge, not a full red outline.
            Spacer(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        if (status.tone == CustomerTone.OVERDUE) accent else Color.Transparent
                    ),
            )
            Box(
                modifier = Modifier
                    .padding(start = NeoSpacing.md)
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, NeoShapes.row),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = CustomerDirectoryPresentation.initials(row.customer.name),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = NeoSpacing.md),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = row.customer.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (status.tone == CustomerTone.SETTLED) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (status.tone) {
                            CustomerTone.OVERDUE -> Icons.Filled.Warning
                            CustomerTone.SETTLED -> Icons.Filled.CheckCircle
                            CustomerTone.PENDING -> Icons.Filled.DateRange
                        },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(NeoSpacing.xs))
                    Text(
                        text = status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                NeoMoney(
                    amountCents = row.balanceCents,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (status.tone) {
                        CustomerTone.OVERDUE -> MaterialTheme.colorScheme.error
                        CustomerTone.SETTLED -> MaterialTheme.colorScheme.onSurfaceVariant
                        CustomerTone.PENDING -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Opciones de ${row.customer.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Editar") },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Eliminar", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onArchive() },
                    )
                }
            }
        }
    }
}
