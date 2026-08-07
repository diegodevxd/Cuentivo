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
import com.cuentivo.app.ui.NeoSpacing
import java.time.LocalDate

/** The scroll container needs a handle so a 320 dp test can reach the total and the action. */
const val NEW_SALE_LIST_TAG = "new-sale-list"

/**
 * The derived total, named rather than matched by its text. A line subtotal can legitimately print
 * the same figure as the total, and a test that cannot tell the two apart is not testing the total.
 */
const val SALE_TOTAL_TAG = "sale-total"

/** Compact enough for two lines plus a remove action, dense enough to show a real cart. */
private val CART_ROW_HEIGHT = 60.dp

/**
 * The four due dates the reference offers. The label is what a person picks; the days are what
 * becomes `dueDateEpochDay`, and "Sin fecha" stays null rather than quietly meaning "today".
 */
private enum class DueOption(val labelRes: Int, val days: Long?) {
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
    onExit: () -> Unit = {},
) {
    val correcting = initialDraft != null
    var customer by remember(initialCustomer?.customer?.id) {
        mutableStateOf(preselectedCustomer(initialCustomer, customers))
    }
    var cart by remember(initialDraft) { mutableStateOf(initialDraft ?: SaleDraft()) }
    var picked by remember { mutableStateOf<ProductEntity?>(null) }
    var quantityText by remember { mutableStateOf("1") }
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
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
        ) {
            item {
                NeoTaskHeader(
                    eyebrow = stringResource(R.string.new_sale_eyebrow),
                    title = stringResource(if (correcting) R.string.new_sale_correct_title else R.string.new_sale_title),
                    onBack = onExit,
                    backDescription = stringResource(R.string.new_sale_back_description),
                )
            }
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
                )
            }
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
                )
            }
            item {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { raw -> quantityText = raw.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.new_sale_quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = NeoShapes.row,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                val chosenProduct = picked
                NeoAction(
                    label = stringResource(R.string.new_sale_add_to_cart),
                    onClick = {
                        if (chosenProduct != null && quantity > 0) {
                            cart = cart.withLine(
                                SaleDraftLine(
                                    productId = chosenProduct.id,
                                    productName = chosenProduct.name,
                                    quantity = quantity,
                                    unitPriceCents = chosenProduct.salePriceCents,
                                )
                            )
                            picked = null
                            quantityText = "1"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = chosenProduct != null && quantity > 0,
                    primary = false,
                )
            }

            item { SectionLabel(stringResource(R.string.new_sale_cart)) }
            if (draft.isEmpty) {
                item { EmptyCartRow() }
            } else {
                items(draft.lines, key = { it.productId }) { line ->
                    CartRow(line) { cart = cart.withoutProduct(line.productId) }
                }
            }

            item { MoneyField(stringResource(R.string.new_sale_discount_label), discountText) { discountText = it } }

            item { SectionLabel(stringResource(R.string.new_sale_due_question)) }
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

@Composable
private fun EmptyCartRow() {
    Card(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = CART_ROW_HEIGHT),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = CART_ROW_HEIGHT)
                .padding(horizontal = NeoSpacing.md, vertical = NeoSpacing.sm),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.new_sale_empty_cart_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.new_sale_empty_cart_detail),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

@Composable
private fun TotalPanel(draft: SaleDraft, discountCents: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NeoShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(NeoSpacing.md)) {
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
                    style = MaterialTheme.typography.headlineSmall,
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
