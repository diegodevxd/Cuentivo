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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.runtime.LaunchedEffect
import com.cuentivo.app.domain.NewCustomerDraft
import com.cuentivo.app.domain.OpeningBalanceRow
import com.cuentivo.app.domain.QuickSaleCart
import com.cuentivo.app.domain.QuickSaleEntry
import com.cuentivo.app.domain.QuickSalePolicy
import com.cuentivo.app.data.local.ProductImageStore
import com.cuentivo.app.ui.product.ProductPhotoDraftPolicy
import com.cuentivo.app.ui.product.ProductPhotoPicker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import java.util.UUID
import com.cuentivo.app.ui.FormDialog
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoEmptyState
import com.cuentivo.app.ui.NeoMoney
import com.cuentivo.app.ui.NeoSectionHeader
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NavigationLayoutPolicy
import com.cuentivo.app.ui.NeoSpacing
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.ui.transaction.DueOption
import com.cuentivo.app.ui.transaction.NeoChoiceChip
import com.cuentivo.app.ui.transaction.NeoSelectorField
import androidx.compose.material.icons.filled.Close
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
    /**
     * The catalog, so the quick sale on the create form can take rows from it instead of making the
     * person retype a product they already have.
     */
    products: List<ProductEntity> = emptyList(),
    onCreateCustomer: (NewCustomerDraft) -> Unit = {},
    /** The whole notebook at once. All-or-nothing: the host writes every row or none of them. */
    onImportOpeningBalances: (List<OpeningBalanceRow>) -> Unit = {},
    /** True while that import is being written; closing is the host's job, as with the form. */
    isImportingBalances: Boolean = false,
    /** Bumped by the host once the import actually lands, which is what closes the sheet. */
    balancesImportedToken: Int = 0,
    onEditCustomer: (CustomerBalanceRow, String, String?, Long?) -> Unit = { _, _, _, _ -> },
    onArchiveCustomer: (CustomerBalanceRow) -> Unit = {},
    onOpen: (CustomerBalanceRow) -> Unit = {},
    /**
     * Opens CuentivoAI straight on the notebook-photo import (camera or gallery). The photo entry
     * used to live only inside the AI lab, behind the robot, so the action that said "from my
     * notebook" was really a hand-typed form — the photo was hidden one screen away.
     */
    onOpenLedgerPhoto: () -> Unit = {},
    /**
     * Whether a create is in flight. The host closes the form itself once the write actually
     * succeeds — see `CuentivoApp`'s `customerSavedToken` — so this screen's only job with it is to
     * disable Guardar for as long as it is true, which is what stops a double tap from firing the
     * callback twice.
     */
    isSaving: Boolean = false,
    /**
     * A filter handed in from outside (a reminder notification tap). It is applied for as long as
     * the screen is showing it; the host clears the request when the owner leaves Clientes, so a
     * later visit returns to "Todos".
     */
    initialFilter: CustomerFilter? = null,
    /**
     * Bumped by the host when the bar is tapped on the tab already showing: back to the root of the
     * tab, which means the search box empty and the "Todos" chip. 0 means "never tapped", so the
     * first composition is not treated as a reset.
     */
    resetToken: Int = 0,
    /**
     * Aviso que se dibuja dentro del formulario de Nuevo cliente (v46).
     *
     * Existe porque el snackbar del host queda detrás del diálogo: "primero crea tu negocio" era
     * invisible justo cuando hacía falta, con el formulario abierto y "Guardar" sin hacer nada.
     */
    createFormNotice: String? = null,
) {
    val customersNewCustomerDescription = stringResource(R.string.customers_new_customer_description)
    var query by remember { mutableStateOf("") }
    // Keyed on the incoming filter so the reminder tap lands with the overdue chip already
    // chosen at first composition — no async round-trip. The host only clears the request when the
    // owner leaves Clientes, so a later visit starts at "Todos" instead of re-applying.
    var chip by remember(initialFilter) { mutableStateOf(initialFilter ?: CustomerFilter.ALL) }
    var editing by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    var archiving by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    var importingBalances by remember { mutableStateOf(false) }
    // Same rule as the customer form: the sheet closes on a write that landed, never on the tap.
    LaunchedEffect(balancesImportedToken) {
        if (balancesImportedToken > 0) importingBalances = false
    }
    // Tapping the tab you are already on returns to the root: no search, no filter, no half-open
    // form. Guarded on > 0 so the very first composition keeps whatever `initialFilter` asked for.
    LaunchedEffect(resetToken) {
        if (resetToken > 0) {
            query = ""
            chip = CustomerFilter.ALL
            editing = null
            archiving = null
            importingBalances = false
        }
    }
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
                bottom = NavigationLayoutPolicy.bottomBarClearanceFor(
                    NavigationLayoutPolicy.modeFor(maxWidth, LocalDensity.current.fontScale),
                ),
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
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
                ) {
                    TextButton(
                        onClick = { importingBalances = true },
                        modifier = Modifier.weight(1f).testTag("open-debt-import"),
                    ) {
                        Text(stringResource(R.string.opening_import_action))
                    }
                    // The photo is the action that actually matches "mi libreta"; typing rows by
                    // hand is the fallback, not the headline.
                    TextButton(
                        onClick = onOpenLedgerPhoto,
                        modifier = Modifier.weight(1f).testTag("open-ledger-photo"),
                    ) {
                        Text(stringResource(R.string.opening_import_photo_action))
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
            products = products,
            onConfirm = onCreateCustomer,
            onDismiss = { onShowFormChange(false) },
            isSaving = isSaving,
            blockedNotice = createFormNotice,
        )
    }
    if (importingBalances) {
        OpeningBalanceImportDialog(
            customers = customers,
            onConfirm = onImportOpeningBalances,
            onDismiss = { importingBalances = false },
            isSaving = isImportingBalances,
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
            onConfirm = { draft ->
                // Editing only ever touches the descriptive fields; the rest of the draft is off in
                // this mode, because `quickSaleTargetId` is null.
                onEditCustomer(row, draft.name, draft.phone, draft.creditLimitCents)
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
    onConfirm: (NewCustomerDraft) -> Unit,
    onDismiss: () -> Unit,
    initialCreditLimitCents: Long? = null,
    formKey: Any? = Unit,
    note: String? = null,
    isSaving: Boolean = false,
    /**
     * Aviso por el que Guardar no puede proceder, dibujado aquí y no en un snackbar (v46): el
     * snackbar vive detrás de este diálogo, así que la persona pulsaba Guardar y no veía nada.
     */
    blockedNotice: String? = null,
    /** Non-null only when creating: enables the optional "took something with them" section. */
    quickSaleTargetId: String? = null,
    /** Empty while editing, and empty for a shop that keeps no catalog. */
    products: List<ProductEntity> = emptyList(),
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

    // The rows already added, and separately the row being typed. Keeping them apart is what lets
    // Guardar still work for somebody who filled one product and never pressed "Agregar": the
    // pending row is folded in at confirm time, exactly as this form behaved when it held one.
    var cart by remember(formKey) { mutableStateOf(QuickSaleCart()) }
    var pickedProduct by remember(formKey) { mutableStateOf<ProductEntity?>(null) }
    var fromCatalog by remember(formKey, products.isEmpty()) { mutableStateOf(products.isNotEmpty()) }
    var productName by remember(formKey) { mutableStateOf("") }
    var productPriceText by remember(formKey) { mutableStateOf("") }
    var quantityText by remember(formKey) { mutableStateOf(QuickSalePolicy.DEFAULT_QUANTITY) }
    var photo by remember(formKey) { mutableStateOf(ProductPhotoDraftPolicy.start(null)) }
    // Each added row needs its own id, and so does the photo file the picker writes for it.
    var pendingLineId by remember(formKey) { mutableStateOf(UUID.randomUUID().toString()) }
    val context = LocalContext.current
    val imageStore = remember(context) { ProductImageStore(context) }
    // Starts on the default plazo, like Nueva venta. Selling fiado means agreeing on a date, and
    // this route used to save none at all — every customer created this way stayed invisible to the
    // payment score forever.
    var dueOption by remember(formKey) { mutableStateOf(DueOption.FIFTEEN) }
    val chosen = pickedProduct
    val quickSaleEntry = when {
        quickSaleTargetId == null -> QuickSaleEntry.Empty
        // A catalog pick is read from the product, so no name or price has to be typed at all.
        fromCatalog && chosen != null -> QuickSalePolicy.read(
            pendingLineId, chosen.id, chosen.name, Money.pesosText(chosen.salePriceCents), quantityText, null,
        )
        fromCatalog -> QuickSaleEntry.Empty
        else -> QuickSalePolicy.read(pendingLineId, null, productName, productPriceText, quantityText, photo.current)
    }
    val pendingLine = (quickSaleEntry as? QuickSaleEntry.Ready)?.line
    // What actually gets sold: the rows added plus whatever is still in the row being typed.
    val quickSale = cart
        .let { current -> pendingLine?.let(current::withLine) ?: current }
        .copy(dueInDays = dueOption.days)
        .takeIf { !it.isEmpty }
    // What they already owed before this app existed. Only offered while creating: an opening
    // balance for somebody who is already in the ledger would be a second, unexplained debt.
    var openingBalanceText by remember(formKey) { mutableStateOf("") }
    val openingBalanceCents = openingBalanceText.trim().ifBlank { null }
        ?.let { Money.positiveCentsFromPesos(it) }
    val openingBalanceValid = openingBalanceText.isBlank() || openingBalanceCents != null
    val nameLabel = stringResource(R.string.customers_form_name_label)
    val phoneLabel = stringResource(R.string.customers_form_phone_label)
    val creditLabel = stringResource(R.string.customers_form_credit_limit_label)
    val noCreditLabel = stringResource(R.string.customers_form_credit_limit_none)
    val invalidCreditLabel = stringResource(R.string.customers_form_credit_limit_invalid)
    val creditHint = stringResource(R.string.customers_form_credit_limit_hint)
    // One definition of "this form can be saved", so the Guardar button and the secondary action
    // can never disagree about it.
    val canSave = name.isNotBlank() && creditLimitValid && openingBalanceValid && !isSaving &&
        quickSaleEntry != QuickSaleEntry.Incomplete
    val draftOf = { continueToSale: Boolean ->
        NewCustomerDraft(
            name = name.trim(),
            phone = phone.trim().ifBlank { null },
            creditLimitCents = creditLimitCents,
            quickSale = quickSale,
            openingBalanceCents = openingBalanceCents,
            // La deuda anterior usa el mismo plazo que la venta rápida: una deuda sin fecha no
            // vence ni entra al recordatorio (v44).
            openingBalanceDueInDays = openingBalanceCents?.let { dueOption.days },
            continueToSale = continueToSale,
        )
    }
    FormDialog(
        title = title,
        // A half-filled product blocks Guardar rather than saving a bare customer: somebody who
        // typed what they were selling did not mean to lose it.
        confirmEnabled = canSave,
        onConfirm = { if (canSave) onConfirm(draftOf(false)) },
        onDismiss = {
            // Nothing points at a photo taken for a customer who was never saved.
            ProductPhotoDraftPolicy.onCancel(photo).forEach(imageStore::delete)
            onDismiss()
        },
    ) {
        // Arriba y no al final: al fondo de un formulario largo el aviso queda fuera de la pantalla,
        // que es la misma forma de "tapado" que el snackbar detrás del diálogo (v46).
        blockedNotice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().testTag("customer-form-blocked-notice"),
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(nameLabel) },
            // Next: the keyboard's action key walks into Teléfono instead of dismissing.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("customer-name-field"),
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { raw -> phone = raw.filter { it.isDigit() || it == '+' }.take(15) },
            label = { Text(phoneLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            singleLine = true,
            shape = NeoShapes.row,
            modifier = Modifier.fillMaxWidth(),
        )
        if (quickSaleTargetId != null) {
            NeoSectionHeader(title = stringResource(R.string.customers_form_opening_balance_title))
            OutlinedTextField(
                value = openingBalanceText,
                onValueChange = { raw -> openingBalanceText = raw.filter { it.isDigit() || it == '.' } },
                label = { Text(stringResource(R.string.customers_form_opening_balance_label)) },
                prefix = { Text("$ ") },
                supportingText = {
                    when {
                        openingBalanceText.isBlank() -> {
                            Text(stringResource(R.string.customers_form_opening_balance_hint))
                        }
                        openingBalanceCents == null -> Text(
                            stringResource(R.string.customers_form_opening_balance_invalid),
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Text(
                            stringResource(
                                R.string.customers_form_opening_balance_detail,
                                Money.format(openingBalanceCents),
                            )
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = NeoShapes.row,
                modifier = Modifier.fillMaxWidth().testTag("opening-balance-field"),
            )
            // "¿Cuándo debe pagar?" va junto a "¿Ya te debía de antes?" (v45): el plazo aplica a la
            // deuda anterior y a la venta rápida por igual, y al fondo del formulario quedaba lejos
            // de la pregunta que lo justifica. Se dibuja una sola vez, aquí.
            DueDateQuestion(dueOption = dueOption, onSelect = { dueOption = it })
            NeoSectionHeader(title = stringResource(R.string.customers_form_quick_sale_title))
            Text(
                stringResource(R.string.customers_form_quick_sale_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Everything already added, each with its own way out. Drawn above the entry fields so
            // a growing list does not push what you are typing off the bottom of the dialog.
            cart.lines.forEach { line ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(
                            R.string.customers_form_quick_sale_line,
                            line.quantity,
                            line.productName,
                            Money.format(line.totalCents),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { cart = cart.withoutLine(line.lineId) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(
                                R.string.customers_form_quick_sale_remove,
                                line.productName,
                            ),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Only offered when there is a catalog to pick from: two chips where one leads to an
            // empty list is a dead end, not a choice.
            if (products.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm)) {
                    NeoChoiceChip(
                        label = stringResource(R.string.new_sale_source_catalog),
                        isSelected = fromCatalog,
                        onClick = { fromCatalog = true },
                        modifier = Modifier.weight(1f),
                    )
                    NeoChoiceChip(
                        label = stringResource(R.string.new_sale_source_manual),
                        isSelected = !fromCatalog,
                        onClick = { fromCatalog = false },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (fromCatalog && products.isNotEmpty()) {
                NeoSelectorField(
                    label = stringResource(R.string.new_sale_product_label),
                    selected = pickedProduct,
                    placeholder = stringResource(R.string.new_sale_product_placeholder),
                    options = products,
                    optionTitle = { it.name },
                    optionDetail = { Money.format(it.salePriceCents) },
                    emptyText = stringResource(R.string.new_sale_product_empty),
                    onSelect = { pickedProduct = it },
                )
            } else {
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
            }
            OutlinedTextField(
                value = quantityText,
                onValueChange = { raw -> quantityText = raw.filter { it.isDigit() }.take(4) },
                label = { Text(stringResource(R.string.customers_form_quick_sale_quantity)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = NeoShapes.row,
                modifier = Modifier.fillMaxWidth().testTag("quick-sale-quantity"),
            )
            // A photo only makes sense for a product being created here; a catalog product has one.
            if (!fromCatalog || products.isEmpty()) {
                ProductPhotoPicker(photo, imageStore, pendingLineId) { photo = it }
            }
            TextButton(
                onClick = {
                    pendingLine?.let { line ->
                        cart = cart.withLine(line)
                        // The photo file now belongs to the added row. `provisional` is left alone
                        // on purpose: it is what cancelling the dialog uses to delete every file it
                        // created, and a row that was never saved must not leave one behind.
                        photo = photo.copy(current = null)
                        pendingLineId = UUID.randomUUID().toString()
                        pickedProduct = null
                        productName = ""
                        productPriceText = ""
                        quantityText = QuickSalePolicy.DEFAULT_QUANTITY
                    }
                },
                enabled = pendingLine != null,
                modifier = Modifier.fillMaxWidth().testTag("quick-sale-add-line"),
            ) {
                Text(stringResource(R.string.customers_form_quick_sale_add_line))
            }
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
            // Inside the body rather than beside Guardar: three buttons in an AlertDialog's action
            // row truncate at 320 dp, and this label is the long one. The section above holds a
            // single product; a real order is five or more, and this hands off to the actual cart
            // instead of growing a second one inside a dialog.
            TextButton(
                onClick = { if (canSave) onConfirm(draftOf(true)) },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().testTag("customer-save-and-sell"),
            ) {
                Text(stringResource(R.string.customers_form_save_and_sell))
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
    }
}

/**
 * "¿Cuándo debe pagar?" con sus cuatro plazos (v45).
 *
 * Se dibuja junto a "¿Ya te debía de antes?" porque el plazo aplica tanto a la deuda anterior como a
 * la venta rápida; al fondo del formulario quedaba lejos de la pregunta que lo justifica. Dos por
 * fila, igual que Nueva venta: cuatro chips en una fila truncan "Sin fecha" a 320 dp.
 */
@Composable
private fun DueDateQuestion(dueOption: DueOption, onSelect: (DueOption) -> Unit) {
    Text(
        stringResource(R.string.new_sale_due_question),
        style = MaterialTheme.typography.labelMedium,
    )
    Column(verticalArrangement = Arrangement.spacedBy(NeoSpacing.xs)) {
        DueOption.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm)) {
                pair.forEach { option ->
                    NeoChoiceChip(
                        label = stringResource(option.labelRes),
                        isSelected = dueOption == option,
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
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
        // `fillMaxSize` used to work because the card had a fixed height, so "fill" had something to
        // fill. With a minimum height instead, the incoming max height is unbounded, fill resolves
        // to the content's own height, and the text ended up sitting against the top of a card that
        // was taller than it. IntrinsicSize.Min gives the row a real height to centre within — and
        // is also what lets the overdue accent stripe below reach full height again.
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
