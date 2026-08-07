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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.PaymentReliability
import com.cuentivo.app.ui.theme.NeoOverdue
import com.cuentivo.app.ui.theme.NeoPayment
import com.cuentivo.app.ui.theme.NeoSlow
import com.cuentivo.app.domain.QuickSale
import com.cuentivo.app.domain.QuickSaleEntry
import com.cuentivo.app.domain.QuickSalePolicy
import com.cuentivo.app.data.local.ProductImageStore
import com.cuentivo.app.ui.product.ProductPhotoDraftPolicy
import com.cuentivo.app.ui.product.ProductPhotoPicker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import java.util.UUID
import com.cuentivo.app.ui.FormDialog
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoEmptyState
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
    /**
     * Payment reliability by customer id. Defaults to empty so a caller that has not loaded the
     * scores yet simply shows no dots, rather than every customer briefly reading as unrated.
     */
    scores: Map<String, PaymentReliability> = emptyMap(),
    modifier: Modifier = Modifier,
    showForm: Boolean = false,
    onShowFormChange: (Boolean) -> Unit = {},
    onCreateCustomer: (String, String?, Long?, QuickSale?) -> Unit = { _, _, _, _ -> },
    onEditCustomer: (CustomerBalanceRow, String, String?, Long?) -> Unit = { _, _, _, _ -> },
    onArchiveCustomer: (CustomerBalanceRow) -> Unit = {},
    onOpen: (CustomerBalanceRow) -> Unit = {},
    /**
     * Whether a create is in flight. The host closes the form itself once the write actually
     * succeeds — see `CuentivoApp`'s `customerSavedToken` — so this screen's only job with it is to
     * disable Guardar for as long as it is true, which is what stops a double tap from firing the
     * callback twice.
     */
    isSaving: Boolean = false,
) {
    val customersNewCustomerDescription = stringResource(R.string.customers_new_customer_description)
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
            item { Text(stringResource(R.string.customers_title), style = MaterialTheme.typography.titleLarge) }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.customers_search_placeholder), style = MaterialTheme.typography.bodyLarge)
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
                        FilterChipRow(stringResource(option.labelRes), option == chip) { chip = option }
                    }
                }
            }
            if (visible.isEmpty()) {
                item {
                    // A search that matches nobody is not an invitation to create somebody: the
                    // fix is to change the search, so only the pristine state offers an action.
                    val pristine = query.isBlank() && chip == CustomerFilter.ALL
                    NeoEmptyState(
                        title = if (pristine) {
                            stringResource(R.string.customers_empty_title)
                        } else {
                            stringResource(R.string.customers_no_match_title)
                        },
                        detail = if (pristine) {
                            stringResource(R.string.customers_empty_detail)
                        } else {
                            stringResource(R.string.customers_no_match_detail)
                        },
                        actionLabel = if (pristine) stringResource(R.string.customers_add_action) else null,
                        onAction = if (pristine) ({ onShowFormChange(true) }) else null,
                    )
                }
            } else {
                items(visible, key = { it.customer.id }) { row ->
                    CustomerRow(
                        row = row,
                        status = CustomerDirectoryPresentation.status(row, dueSales, today),
                        reliability = scores[row.customer.id] ?: PaymentReliability.NO_HISTORY,
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
                .size(48.dp)
                .semantics { contentDescription = customersNewCustomerDescription },
            shape = NeoShapes.card,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            // material-icons-extended is deliberately not a dependency, so the "add person" glyph
            // is composed from the two core icons instead of pulling in the whole extended set.
            Box(modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).align(Alignment.BottomStart),
                )
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp).align(Alignment.TopEnd),
                )
            }
        }
    }

    if (showForm) {
        // The photo is written before the product row exists, so it needs a name up front. Same
        // trick the product form uses: ProductEntity.imageUri stores the file name, not the id.
        val newCustomerDraftId = remember(showForm) { UUID.randomUUID().toString() }
        CustomerFormDialog(
            title = stringResource(R.string.customers_form_new_title),
            initialName = "",
            initialPhone = "",
            initialCreditLimitCents = null,
            // No eager close here: the previous version closed on tap, before Room even ran, so a
            // duplicate name or a missing business failed in total silence — the dialog was already
            // gone by the time anything went wrong. Closing is now the host's job, done only once
            // `onCreateCustomer` actually succeeds.
            quickSaleTargetId = newCustomerDraftId,
            onConfirm = { name, phone, creditLimitCents, quickSale ->
                onCreateCustomer(name, phone, creditLimitCents, quickSale)
            },
            onDismiss = { onShowFormChange(false) },
            isSaving = isSaving,
        )
    }
    editing?.let { row ->
        CustomerFormDialog(
            title = stringResource(R.string.customers_form_edit_title),
            initialName = row.customer.name,
            initialPhone = row.customer.phone.orEmpty(),
            initialCreditLimitCents = row.customer.creditLimitCents,
            formKey = row.customer.id,
            note = stringResource(R.string.customers_form_edit_note),
            onConfirm = { name, phone, creditLimitCents, _ ->
                onEditCustomer(row, name, phone, creditLimitCents)
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
                        stringResource(R.string.customers_delete_confirm_title, row.customer.name)
                    } else {
                        stringResource(R.string.customers_delete_has_balance_title, row.customer.name)
                    }
                )
            },
            text = {
                Text(
                    if (settled) {
                        stringResource(R.string.customers_delete_confirm_detail)
                    } else {
                        if (row.balanceCents < 0L) {
                            stringResource(R.string.customers_delete_has_credit_detail, Money.format(-row.balanceCents))
                        } else {
                            stringResource(R.string.customers_delete_has_balance_detail, Money.format(row.balanceCents))
                        }
                    }
                )
            },
            confirmButton = {
                if (settled) {
                    TextButton(onClick = { archiving = null; onArchiveCustomer(row) }) {
                        Text(stringResource(R.string.customers_delete_confirm_action), color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = { archiving = null; onOpen(row) }) {
                        Text(stringResource(R.string.customers_view_statement_action))
                    }
                }
            },
            dismissButton = { TextButton(onClick = { archiving = null }) { Text(stringResource(R.string.customers_cancel_action)) } },
        )
    }
}

@Composable
private fun CustomerFormDialog(
    title: String,
    initialName: String,
    initialPhone: String,
    onConfirm: (String, String?, Long?, QuickSale?) -> Unit,
    onDismiss: () -> Unit,
    initialCreditLimitCents: Long? = null,
    formKey: Any? = Unit,
    note: String? = null,
    isSaving: Boolean = false,
    /** Non-null only when creating: enables the optional "took something with them" section. */
    quickSaleTargetId: String? = null,
) {
    // Keyed on the customer so reopening for another row starts from that row's data.
    var name by remember(formKey) { mutableStateOf(initialName) }
    var phone by remember(formKey) { mutableStateOf(initialPhone) }
    var creditLimitText by remember(formKey) {
        mutableStateOf(initialCreditLimitCents?.let { Money.pesosText(it) }.orEmpty())
    }
    // Blank means "sin límite", same as null in the database — an invalid, non-blank amount must
    // not silently save as no-limit, so it blocks Guardar instead. Zero is rejected the same way
    // as any other invalid entry: a $0 limit would flag every future sale as over-limit, which
    // reads as a bug, not a deliberate "no credit" setting.
    val creditLimitCents = creditLimitText.trim().ifBlank { null }?.let { Money.positiveCentsFromPesos(it) }
    val creditLimitValid = creditLimitText.isBlank() || creditLimitCents != null

    var productName by remember(formKey) { mutableStateOf("") }
    var productPriceText by remember(formKey) { mutableStateOf("") }
    var quantityText by remember(formKey) { mutableStateOf(QuickSalePolicy.DEFAULT_QUANTITY) }
    var photo by remember(formKey) { mutableStateOf(ProductPhotoDraftPolicy.start(null)) }
    val context = LocalContext.current
    val imageStore = remember(context) { ProductImageStore(context) }
    val quickSaleEntry = if (quickSaleTargetId == null) {
        QuickSaleEntry.Empty
    } else {
        QuickSalePolicy.read(productName, productPriceText, quantityText, photo.current)
    }
    val quickSale = (quickSaleEntry as? QuickSaleEntry.Ready)?.sale
    val nameLabel = stringResource(R.string.customers_form_name_label)
    val phoneLabel = stringResource(R.string.customers_form_phone_label)
    val creditLabel = stringResource(R.string.customers_form_credit_limit_label)
    val noCreditLabel = stringResource(R.string.customers_form_credit_limit_none)
    val invalidCreditLabel = stringResource(R.string.customers_form_credit_limit_invalid)
    val creditHint = stringResource(R.string.customers_form_credit_limit_hint)
    FormDialog(
        title = title,
        // A half-filled product blocks Guardar rather than saving a bare customer: somebody who
        // typed what they were selling did not mean to lose it.
        confirmEnabled = name.isNotBlank() && creditLimitValid && !isSaving &&
            quickSaleEntry != QuickSaleEntry.Incomplete,
        onConfirm = {
            if (name.isNotBlank() && creditLimitValid && !isSaving && quickSaleEntry != QuickSaleEntry.Incomplete) {
                onConfirm(name.trim(), phone.trim().ifBlank { null }, creditLimitCents, quickSale)
            }
        },
        onDismiss = {
            // Nothing points at a photo taken for a customer who was never saved.
            ProductPhotoDraftPolicy.onCancel(photo).forEach(imageStore::delete)
            onDismiss()
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(nameLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("customer-name-field"),
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { raw -> phone = raw.filter { it.isDigit() || it == '+' }.take(15) },
            label = { Text(phoneLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = creditLimitText,
            onValueChange = { raw -> creditLimitText = raw.filter { it.isDigit() || it == '.' } },
            label = { Text(creditLabel) },
            prefix = { Text("$ ") },
            supportingText = {
                when {
                    creditLimitText.isBlank() -> Text(noCreditLabel)
                    creditLimitCents == null -> {
                        Text(invalidCreditLabel, color = MaterialTheme.colorScheme.error)
                    }
                    else -> Text(creditHint.format(Money.format(creditLimitCents)))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = NeoShapes.row,
            modifier = Modifier.fillMaxWidth(),
        )
        if (quickSaleTargetId != null) {
            Text(
                stringResource(R.string.customers_form_quick_sale_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.customers_form_quick_sale_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = productName,
                onValueChange = { productName = it },
                label = { Text(stringResource(R.string.customers_form_quick_sale_product)) },
                singleLine = true,
                shape = NeoShapes.row,
                modifier = Modifier.fillMaxWidth().testTag("quick-sale-product"),
            )
            OutlinedTextField(
                value = productPriceText,
                onValueChange = { raw -> productPriceText = raw.filter { it.isDigit() || it == '.' } },
                label = { Text(stringResource(R.string.customers_form_quick_sale_price)) },
                prefix = { Text("$ ") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = NeoShapes.row,
                modifier = Modifier.fillMaxWidth().testTag("quick-sale-price"),
            )
            OutlinedTextField(
                value = quantityText,
                onValueChange = { raw -> quantityText = raw.filter { it.isDigit() }.take(4) },
                label = { Text(stringResource(R.string.customers_form_quick_sale_quantity)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = NeoShapes.row,
                modifier = Modifier.fillMaxWidth().testTag("quick-sale-quantity"),
            )
            ProductPhotoPicker(photo, imageStore, quickSaleTargetId) { photo = it }
            quickSale?.let {
                Text(
                    stringResource(R.string.customers_form_quick_sale_total, Money.format(it.totalCents)),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (quickSaleEntry == QuickSaleEntry.Incomplete) {
                Text(
                    stringResource(R.string.customers_form_quick_sale_incomplete),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
    }
}

/**
 * The reference draws a ~30 dp chip, which is below the 48 dp Android minimum. Rather than inflate
 * the visible pill, the clickable area is an invisible 48 dp box wrapped around it: the drawing
 * stays compact and the finger target is honest.
 */
@Composable
/**
 * The 🟢🟡🔴 dot, drawn only when there is enough history to mean something.
 *
 * It carries a `contentDescription` because color alone is not a message: it says nothing to a
 * screen reader and little to someone who cannot tell red from green.
 */
private fun ReliabilityDot(reliability: PaymentReliability) {
    val labelRes = reliability.labelRes() ?: return
    val labelText = stringResource(labelRes)
    val color = when (reliability) {
        PaymentReliability.PUNCTUAL -> NeoPayment
        PaymentReliability.SLOW -> NeoSlow
        PaymentReliability.DELINQUENT -> NeoOverdue
        PaymentReliability.NO_HISTORY -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .padding(end = NeoSpacing.xs)
            .size(8.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = labelText },
    )
}

@Composable
private fun FilterChipRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Tab
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
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
                .padding(horizontal = NeoSpacing.md),
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
}

@Composable
private fun CustomerRow(
    row: CustomerBalanceRow,
    status: CustomerStatus,
    reliability: PaymentReliability,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val editLabel = stringResource(R.string.customers_edit)
    val deleteLabel = stringResource(R.string.customers_delete)
    val accent = when (status.tone) {
        CustomerTone.OVERDUE -> MaterialTheme.colorScheme.error
        CustomerTone.SETTLED -> MaterialTheme.colorScheme.secondary
        CustomerTone.CREDIT -> MaterialTheme.colorScheme.primary
        CustomerTone.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DIRECTORY_ROW_HEIGHT)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The traffic light answers "should I let this person owe me?", which the
                    // balance alone never did. It is deliberately separate from `status`: owing
                    // money today and being a bad payer are different facts, and a good customer
                    // mid-purchase must not be painted red for it.
                    ReliabilityDot(reliability)
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
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (status.tone) {
                            CustomerTone.OVERDUE -> Icons.Filled.Warning
                            CustomerTone.SETTLED -> Icons.Filled.CheckCircle
                            CustomerTone.CREDIT -> Icons.Filled.CheckCircle
                            CustomerTone.PENDING -> Icons.Filled.DateRange
                        },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(NeoSpacing.xs))
                    Text(
                        text = statusLabel(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                NeoMoney(
                    amountCents = BalancePresenter.from(row.balanceCents).amountCents,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (status.tone) {
                        CustomerTone.OVERDUE -> MaterialTheme.colorScheme.error
                        CustomerTone.SETTLED -> MaterialTheme.colorScheme.onSurfaceVariant
                        CustomerTone.CREDIT -> MaterialTheme.colorScheme.primary
                        CustomerTone.PENDING -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.customers_options_description, row.customer.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(editLabel) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onArchive() },
                    )
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: CustomerStatus): String = when (status.tone) {
    CustomerTone.SETTLED -> stringResource(com.cuentivo.app.R.string.customer_status_settled)
    CustomerTone.CREDIT -> stringResource(com.cuentivo.app.R.string.customer_status_credit)
    CustomerTone.PENDING -> status.daysUntilDue?.let { days ->
        if (days == 0L) stringResource(com.cuentivo.app.R.string.customer_status_due_today)
        else stringResource(com.cuentivo.app.R.string.customer_status_due_in_days, days)
    } ?: stringResource(com.cuentivo.app.R.string.customer_status_pending)
    CustomerTone.OVERDUE -> stringResource(
        if (status.overdueDays == 1L) com.cuentivo.app.R.string.customer_status_overdue_one
        else com.cuentivo.app.R.string.customer_status_overdue_days,
        status.overdueDays ?: 0L,
    )
}
