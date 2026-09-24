package com.cuentivo.app.ui.transaction

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.SaleDraft
import com.cuentivo.app.domain.SaleDraftLine
import com.cuentivo.app.ui.MoneyField
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoMoney
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NavigationLayoutPolicy
import com.cuentivo.app.ui.NeoSpacing
import java.time.LocalDate
import java.util.UUID

/** The scroll container needs a handle so a 320 dp test can reach the total and the action. */
const val NEW_SALE_LIST_TAG = "new-sale-list"

/**
 * The derived total, named rather than matched by its text. A line subtotal can legitimately print
 * the same figure as the total, and a test that cannot tell the two apart is not testing the total.
 */
const val SALE_TOTAL_TAG = "sale-total"

/** The hand-written product name, so a test can reach it without matching a translated label. */
const val MANUAL_NAME_TAG = "manual-line-name"

/** Compact enough for two lines plus a remove action, dense enough to show a real cart. */
private val CART_ROW_HEIGHT = 60.dp

/**
 * The four due dates the reference offers. The label is what a person picks; the days are what
 * becomes `dueDateEpochDay`, and "Sin fecha" stays null rather than quietly meaning "today".
 */
/**
 * Shared with the quick sale on the customer form rather than duplicated: two lists of plazos that
 * drift apart is how the same shop ends up with sales the score treats differently depending on
 * which screen recorded them.
 */
/**
 * Where the next line comes from.
 *
 * [MANUAL] exists because this app was unusable for anyone who sells without a catalog: the seller
 * who writes down "3 trapeadores, $80" has no product list and is not going to build one before
 * making a sale. A manual line writes nothing to `products` — `sale_items.productId` is nullable in
 * Room and in the rules, and the name and price are already snapshotted onto the line.
 */
internal enum class LineSource(val labelRes: Int) {
    CATALOG(R.string.new_sale_source_catalog),
    MANUAL(R.string.new_sale_source_manual),
}

internal enum class DueOption(val labelRes: Int, val days: Long?) {
    NONE(R.string.new_sale_due_none, null),
    SEVEN(R.string.new_sale_due_7, 7L),
    FIFTEEN(R.string.new_sale_due_15, 15L),
    THIRTY(R.string.new_sale_due_30, 30L),
}

/**
 * Nueva venta: pick a customer, add as many products as needed, then register.
 *
 * A focused task rather than a destination — the central `+` opens it, its own back arrow and the
 * system Back button close it, and nothing else in the app launches a sale.
 *
 * The cart is a domain [SaleDraft], so the total printed beside the action is the same integer that
 * reaches the ledger. The discount is deliberately *not* written back into that state: it lives in
 * its own text field, and `draft` below is derived from the two on every composition. Assigning to
 * remembered state while composing is how a screen ends up disagreeing with itself for a frame.
 */
@Composable
fun NewSaleScreen(
    customers: List<CustomerBalanceRow>,
    products: List<ProductEntity>,
    modifier: Modifier = Modifier,
    initialCustomer: CustomerBalanceRow? = null,
    /**
     * The sale being corrected, rebuilt from what was registered. Non-null puts the screen in
     * correction mode: same form, different wording, and the host replaces the sale instead of
     * adding one.
     */
    initialDraft: SaleDraft? = null,
    onCreateSale: (CustomerBalanceRow, SaleDraft) -> Unit = { _, _ -> },
    /**
     * Opens the new-customer form without leaving the sale. Without it an empty customer list was a
     * dead end: the selector said to go to the Clientes tab, and the sale being built was lost on
     * the way. The host returns here with the created customer already selected.
     */
    onAddCustomer: () -> Unit = {},
    onExit: () -> Unit = {},
) {
    val correcting = initialDraft != null
    var customer by remember(initialCustomer?.customer?.id) {
        mutableStateOf(preselectedCustomer(initialCustomer, customers))
    }
    var cart by remember(initialDraft) { mutableStateOf(initialDraft ?: SaleDraft()) }
    var picked by remember { mutableStateOf<ProductEntity?>(null) }
    var quantityText by remember { mutableStateOf("1") }
    // Starts on whatever the shop actually is. A business with an empty catalog offered nothing but
    // an empty selector, which reads as a broken screen rather than as a missing catalog.
    var lineSource by remember(products.isEmpty()) {
        mutableStateOf(if (products.isEmpty()) LineSource.MANUAL else LineSource.CATALOG)
    }
    var manualName by remember { mutableStateOf("") }
    var manualPriceText by remember { mutableStateOf("") }
    var discountText by remember(initialDraft) {
        mutableStateOf(initialDraft?.discountCents?.takeIf { it > 0 }?.let(Money::pesosText).orEmpty())
    }
    // A sale fiado has a date by default, because that is what fiar means. Leaving this on "Sin
    // fecha" meant almost every sale reached the ledger with no agreed date, and a sale that cannot
    // be late is a sale the payment score has to ignore — the traffic light stayed off for everyone.
    // A correction still starts untouched: changing a wrong product must not invent a due date the
    // customer never agreed to (see the fallback where `due` is resolved).
    var dueOption by remember { mutableStateOf(if (correcting) DueOption.NONE else DueOption.FIFTEEN) }

    // Derived, never assigned during composition. `cart` owns the lines, the field owns the text,
    // and the sale that gets validated and submitted is what the two make together.
    val discountCents = if (discountText.isBlank()) 0L else Money.centsFromPesos(discountText) ?: 0L
    val draft = cart.copy(discountCents = discountCents)
    val quantity = quantityText.toIntOrNull() ?: 0
    val validationError = draft.validationError()
    val manualPriceCents = manualPriceText.trim().ifBlank { null }?.let(Money::positiveCentsFromPesos)
    val canAddLine = quantity > 0 && when (lineSource) {
        LineSource.CATALOG -> picked != null
        LineSource.MANUAL -> manualName.isNotBlank() && manualPriceCents != null
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().imePadding()) {
        val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
        LazyColumn(
            modifier = Modifier
                .widthIn(max = layout.contentMaxWidthDp.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .testTag(NEW_SALE_LIST_TAG),
            contentPadding = PaddingValues(
                start = layout.horizontalPaddingDp.dp,
                end = layout.horizontalPaddingDp.dp,
                // Room for the bottom bar and the gesture inset, so the action is never the thing
                // sitting under the system's own handle.
                bottom = NavigationLayoutPolicy.bottomBarClearanceFor(
                    NavigationLayoutPolicy.modeFor(maxWidth, LocalDensity.current.fontScale),
                ),
            ),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
        ) {
            item {
                NeoTaskHeader(
                    title = stringResource(if (correcting) R.string.new_sale_correct_title else R.string.new_sale_title),
                    onBack = onExit,
                    backDescription = stringResource(R.string.new_sale_back_description),
                )
            }
            item { NeoSectionHeader(stringResource(R.string.new_sale_section_customer)) }
            item {
                // Resolved outside the lambda: optionDetail is not a composable slot.
                val balanceFormat = stringResource(R.string.new_sale_customer_balance)
                NeoSelectorField(
                    label = stringResource(R.string.new_sale_customer_label),
                    selected = customer,
                    placeholder = stringResource(R.string.new_sale_customer_placeholder),
                    options = customers,
                    optionTitle = { it.customer.name },
                    optionDetail = { balanceFormat.format(Money.format(it.balanceCents)) },
                    emptyText = stringResource(R.string.new_sale_customer_empty),
                    onSelect = { customer = it },
                    // La hoja vacía ofrece "Agregar cliente" en vez de mandar a la pestaña Clientes,
                    // que ya no es la ruta para crear una clienta desde la venta (v45).
                    emptyActionLabel = stringResource(R.string.customers_add_action),
                    onEmptyAction = onAddCustomer,
                    // The section header above already says CLIENTE.
                    showLabel = false,
                )
            }
            // A way out of the empty-selector dead end, right where the person notices they have no
            // customers. Creating one is a detour, not a different task: the sale in progress is
            // kept and the new customer comes back selected.
            if (customers.isEmpty()) {
                item {
                    NeoAction(
                        label = stringResource(R.string.customers_add_action),
                        onClick = onAddCustomer,
                        modifier = Modifier.fillMaxWidth(),
                        primary = false,
                    )
                }
            }
            item { NeoSectionHeader(stringResource(R.string.new_sale_section_products)) }
            item {
                val catalogLabel = stringResource(R.string.new_sale_source_catalog)
                val manualLabel = stringResource(R.string.new_sale_source_manual)
                NeoSegmentedControl(
                    options = LineSource.entries,
                    selected = lineSource,
                    label = { if (it == LineSource.CATALOG) catalogLabel else manualLabel },
                    onSelect = { lineSource = it },
                )
            }
            if (lineSource == LineSource.CATALOG) {
                item {
                    NeoSelectorField(
                        label = stringResource(R.string.new_sale_product_label),
                        selected = picked,
                        placeholder = stringResource(R.string.new_sale_product_placeholder),
                        options = products,
                        optionTitle = { it.name },
                        optionDetail = { Money.format(it.salePriceCents) },
                        emptyText = stringResource(R.string.new_sale_product_empty),
                        onSelect = { picked = it },
                        showLabel = false,
                    )
                }
            } else {
                item {
                    OutlinedTextField(
                        value = manualName,
                        onValueChange = { manualName = it.take(120) },
                        label = { Text(stringResource(R.string.new_sale_manual_name_label)) },
                        singleLine = true,
                        shape = NeoShapes.row,
                        modifier = Modifier.fillMaxWidth().testTag(MANUAL_NAME_TAG),
                    )
                }
                item {
                    MoneyField(stringResource(R.string.new_sale_manual_price_label), manualPriceText) {
                        manualPriceText = it
                    }
                }
            }
            item {
                // Quantity and the add action on one row: they are one gesture, and stacking them
                // full width made the most common edit on this screen look like two separate steps.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.new_sale_quantity_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NeoQuantityStepper(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        decreaseDescription = stringResource(R.string.new_sale_quantity_decrease),
                        increaseDescription = stringResource(R.string.new_sale_quantity_increase),
                    )
                }
            }
            item {
                val chosenProduct = picked
                NeoAction(
                    label = stringResource(R.string.new_sale_add_to_cart),
                    onClick = {
                        if (canAddLine) {
                            val line = if (lineSource == LineSource.CATALOG && chosenProduct != null) {
                                SaleDraftLine(
                                    lineId = UUID.randomUUID().toString(),
                                    productId = chosenProduct.id,
                                    productName = chosenProduct.name,
                                    quantity = quantity,
                                    unitPriceCents = chosenProduct.salePriceCents,
                                )
                            } else {
                                SaleDraftLine(
                                    lineId = UUID.randomUUID().toString(),
                                    // No product id and nothing written to the catalog: what was
                                    // sold is already snapshotted onto the line itself.
                                    productId = null,
                                    productName = manualName.trim(),
                                    quantity = quantity,
                                    unitPriceCents = requireNotNull(manualPriceCents),
                                )
                            }
                            cart = cart.withLine(line)
                            picked = null
                            manualName = ""
                            manualPriceText = ""
                            quantityText = "1"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canAddLine,
                    primary = false,
                )
            }

            item { NeoSectionHeader(stringResource(R.string.new_sale_cart)) }
            if (draft.isEmpty) {
                item { EmptyCartRow() }
            } else {
                items(draft.lines, key = { it.lineId }) { line ->
                    CartRow(line) { cart = cart.withoutLine(line.lineId) }
                }
            }

            item { NeoSectionHeader(stringResource(R.string.new_sale_section_conditions)) }
            item { MoneyField(stringResource(R.string.new_sale_discount_label), discountText) { discountText = it } }
            item {
                Text(
                    text = stringResource(R.string.new_sale_due_question),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                // Two by two rather than one row of four: at 320 dp four chips would get about
                // 66 dp each, which truncates "Sin fecha" and shrinks every target.
                Column(verticalArrangement = Arrangement.spacedBy(NeoSpacing.xs)) {
                    DueOption.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm)) {
                            pair.forEach { option ->
                                NeoChoiceChip(
                                    label = stringResource(option.labelRes),
                                    isSelected = dueOption == option,
                                    onClick = { dueOption = option },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            // Total and action share one item so no amount of scrolling can separate the figure
            // from the button that commits it.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm)) {
                    TotalPanel(draft = draft, discountCents = discountCents)
                    val selectedCustomer = customer
                    val creditLimitCents = selectedCustomer?.customer?.creditLimitCents
                    if (selectedCustomer != null && creditLimitCents != null && !draft.isEmpty) {
                        val projectedBalanceCents = selectedCustomer.balanceCents + draft.totalCents
                        if (projectedBalanceCents > creditLimitCents) {
                            // A warning, not a block: the owner is standing in front of the customer
                            // and needs to decide, not be stopped by a field they set themselves.
                            Text(
                                text = stringResource(
                                    R.string.new_sale_credit_warning,
                                    selectedCustomer.customer.name,
                                    Money.format(projectedBalanceCents),
                                    Money.format(creditLimitCents),
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (customer == null && !draft.isEmpty) {
                        Text(
                            text = stringResource(R.string.new_sale_missing_customer),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    val chosenCustomer = customer
                    NeoAction(
                        label = stringResource(if (correcting) R.string.new_sale_save_correction else R.string.new_sale_register),
                        onClick = {
                            if (chosenCustomer != null && validationError == null) {
                                // Untouched selector keeps the date the sale already had, so a
                                // correction about a wrong product cannot silently drop its due date.
                                val due = dueOption.days
                                    ?.let { LocalDate.now().plusDays(it).toEpochDay() }
                                    ?: initialDraft?.dueDateEpochDay
                                onCreateSale(chosenCustomer, draft.copy(dueDateEpochDay = due))
                                customer = null
                                cart = SaleDraft()
                                discountText = ""
                                // Back to the default, not to NONE: the next sale on this screen is
                                // a new one, and it deserves the same date the first one got.
                                dueOption = DueOption.FIFTEEN
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = chosenCustomer != null && validationError == null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = NeoSpacing.xs),
    )
}

/**
 * Nothing in the cart yet.
 *
 * One muted line, not a card. A bordered surface the size of a real row announced an emptiness that
 * needs no announcing — the cart is visibly empty — and pushed the total further off screen on the
 * one screen where the total matters most.
 */
@Composable
private fun EmptyCartRow() {
    Text(
        text = stringResource(R.string.new_sale_empty_cart_title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NeoSpacing.sm),
    )
}

@Composable
private fun CartRow(line: SaleDraftLine, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = CART_ROW_HEIGHT),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = NeoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // The name owns its line and elides. A long product name must not push the amount
                // off the row at 320 dp: the amount is the part nobody can guess.
                Text(
                    text = stringResource(R.string.new_sale_cart_line, line.quantity, line.productName),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.new_sale_unit_price, Money.format(line.unitPriceCents)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            NeoMoney(
                amountCents = line.subtotalCents,
                modifier = Modifier.padding(start = NeoSpacing.sm),
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.new_sale_remove_description, line.productName),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The end of the flow, and the only figure on the screen that has to be unmistakable.
 *
 * Deliberately unlike every input above it: filled surface rather than an outlined field, cyan rule
 * across the top, and the amount at display size. Everything above is something you are still
 * deciding; this is what you are about to commit to somebody's account.
 */
@Composable
private fun TotalPanel(draft: SaleDraft, discountCents: Long) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = NeoSpacing.sm),
        shape = NeoShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(NeoSpacing.lg)) {
            Text(
                text = stringResource(R.string.new_sale_total_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(NeoSpacing.xs))
            Box(Modifier.fillMaxWidth()) {
                NeoMoney(
                    amountCents = draft.totalCents,
                    modifier = Modifier.testTag(SALE_TOTAL_TAG),
                    // A step up from every other figure on the screen: the total is the one number
                    // the person is actually agreeing to.
                    style = MaterialTheme.typography.displaySmall,
                )
            }
            if (discountCents > 0) {
                Spacer(Modifier.height(NeoSpacing.xs))
                Text(
                    text = stringResource(R.string.new_sale_before_discount, Money.format(draft.subtotalCents)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
