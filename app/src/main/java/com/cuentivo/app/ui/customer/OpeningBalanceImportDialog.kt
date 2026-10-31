package com.cuentivo.app.ui.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.CustomerNameNormalizer
import com.cuentivo.app.domain.Money
import com.cuentivo.app.ui.transaction.NeoSelectorField
import com.cuentivo.app.domain.OpeningBalanceRow
import com.cuentivo.app.ui.FormDialog
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing

/** How many blank rows the sheet opens with, so it reads as a list rather than a single field. */
private const val INITIAL_ROWS = 3

/**
 * "Traer mis deudas de la libreta": a list of name + amount, typed fast.
 *
 * Somebody arriving from a paper notebook has twenty debts and no patience for twenty trips through
 * the customer form. Until now the only way to record one at all was to invent a product called
 * "deuda anterior", which is what this replaces.
 *
 * Every row is checked here as it is typed — a blank name, an amount that is not a positive number,
 * the same person twice — so nothing is confirmed that the planner would then reject. The planner is
 * still the authority at write time; this only keeps the person from confirming a batch that cannot
 * land, because the import is all-or-nothing and nothing financial can be edited afterwards.
 */
@Composable
internal fun OpeningBalanceImportDialog(
    customers: List<CustomerBalanceRow>,
    onConfirm: (List<OpeningBalanceRow>) -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
) {
    val names = remember { mutableStateListOf<String>().apply { repeat(INITIAL_ROWS) { add("") } } }
    val amounts = remember { mutableStateListOf<String>().apply { repeat(INITIAL_ROWS) { add("") } } }

    // A row nobody touched is not an error; it is just a spare line waiting to be used.
    val filled = names.indices.filter { names[it].isNotBlank() || amounts[it].isNotBlank() }
    val amountCentsAt = names.indices.associateWith { Money.positiveCentsFromPesos(amounts[it].trim()) }
    val duplicateRows = filled
        .filter { names[it].isNotBlank() }
        .groupBy { CustomerNameNormalizer.key(names[it]) }
        .values
        .filter { it.size > 1 }
        .flatten()
        .toSet()
    val rowErrors = filled.associateWith { index ->
        when {
            names[index].isBlank() -> R.string.opening_import_error_name
            amountCentsAt[index] == null -> R.string.opening_import_error_amount
            index in duplicateRows -> R.string.opening_import_error_duplicate
            else -> null
        }
    }
    val totalCents = filled.filter { rowErrors[it] == null }.sumOf { amountCentsAt[it] ?: 0L }
    val canConfirm = filled.isNotEmpty() && rowErrors.values.all { it == null } && !isSaving
    val selectCustomerLabel = stringResource(R.string.opening_import_select_customer)
    val noCustomersLabel = stringResource(R.string.opening_import_no_customers)
    val newCustomerLabel = stringResource(R.string.opening_import_new_customer)

    FormDialog(
        title = stringResource(R.string.opening_import_title),
        confirmEnabled = canConfirm,
        onConfirm = {
            if (canConfirm) {
                onConfirm(filled.map { OpeningBalanceRow(names[it].trim(), amounts[it].trim()) })
            }
        },
        onDismiss = onDismiss,
    ) {
        Text(
            stringResource(R.string.opening_import_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        names.indices.forEach { index ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(NeoSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    NeoSelectorField(
                        label = selectCustomerLabel,
                        selected = customers.firstOrNull { CustomerNameNormalizer.key(it.customer.name) == CustomerNameNormalizer.key(names[index]) },
                        placeholder = selectCustomerLabel,
                        options = customers,
                        optionTitle = { it.customer.name },
                        optionDetail = { null },
                        emptyText = noCustomersLabel,
                        onSelect = { names[index] = it.customer.name },
                        showLabel = false,
                        modifier = Modifier.testTag("opening-import-customer-$index"),
                    )
                    OutlinedTextField(
                        value = names[index],
                        onValueChange = { names[index] = it },
                        label = { Text(newCustomerLabel) },
                        singleLine = true,
                        shape = NeoShapes.row,
                        modifier = Modifier.fillMaxWidth().testTag("opening-import-name-$index"),
                    )
                }
                OutlinedTextField(
                    value = amounts[index],
                    onValueChange = { raw -> amounts[index] = raw.filter { it.isDigit() || it == '.' } },
                    label = { Text(stringResource(R.string.opening_import_amount)) },
                    prefix = { Text("$ ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = NeoShapes.row,
                    // Fixed rather than weighted: the amount is the part nobody can guess, and a
                    // long name must not squeeze it down to three digits.
                    modifier = Modifier.width(132.dp).testTag("opening-import-amount-$index"),
                )
                // Removing is only offered past the rows the sheet opened with, so the list can
                // never shrink to nothing and leave a dialog with no way to type anything.
                if (names.size > INITIAL_ROWS) {
                    IconButton(
                        onClick = { names.removeAt(index); amounts.removeAt(index) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.opening_import_remove_row),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            rowErrors[index]?.let { error ->
                Text(
                    stringResource(error),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(
            onClick = { names.add(""); amounts.add("") },
            modifier = Modifier.fillMaxWidth().testTag("opening-import-add-row"),
        ) {
            Text(stringResource(R.string.opening_import_add_row))
        }
        if (totalCents > 0) {
            Text(
                stringResource(R.string.opening_import_total, Money.format(totalCents)),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            stringResource(R.string.opening_import_warning),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
