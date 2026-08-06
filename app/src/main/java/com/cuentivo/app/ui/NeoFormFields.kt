package com.cuentivo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.domain.Money

/**
 * Form fields shared by every screen that asks a person for money or for a short record.
 *
 * They sit in the neutral `ui` package on purpose. The product catalog and the transaction task
 * screens both need a price field, and a product form must never have to depend on the sale screen
 * to get one.
 */

/**
 * Amount entry in pesos ("150.50"), converted to integer cents only at submit time.
 * Delegates to Money so the screens, the share texts and the CSV all agree. No Double anywhere.
 */
internal fun pesosToCents(text: String): Long? = Money.positiveCentsFromPesos(text)

@Composable
internal fun MoneyField(label: String, text: String, onTextChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = { raw -> onTextChange(raw.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        prefix = { Text("$ ") },
        supportingText = {
            val cents = pesosToCents(text)
            when {
                text.isBlank() -> Text(stringResource(R.string.form_amount_hint))
                cents == null -> Text(stringResource(R.string.form_amount_invalid), color = MaterialTheme.colorScheme.error)
                else -> Text(stringResource(R.string.form_amount_preview, Money.format(cents)))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        shape = NeoShapes.row,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun FormDialog(
    title: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        // Scrollable since the product form grew a photo box: an AlertDialog clips its text slot
        // instead of scrolling it, so the last field simply became unreachable on a short screen.
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
                content = content,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(stringResource(R.string.form_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.form_cancel)) } },
    )
}
