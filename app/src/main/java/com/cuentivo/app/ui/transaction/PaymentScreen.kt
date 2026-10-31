package com.cuentivo.app.ui.transaction

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cuentivo.app.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.Money
import com.cuentivo.app.ui.MoneyField
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NavigationLayoutPolicy
import com.cuentivo.app.ui.NeoSpacing
import com.cuentivo.app.ui.pesosToCents

/** The scroll container needs a handle so a 320 dp test can reach the action. */
const val PAYMENT_LIST_TAG = "payment-list"

/** The two figures, named so a test asks for one of them rather than for a matching string. */
const val PAYMENT_BALANCE_TAG = "payment-current-balance"
const val PAYMENT_RESULT_TAG = "payment-resulting-balance"

/**
 * Abonos: choose who paid, type what they handed over, confirm.
 *
 * Deliberately narrow. There is no payment method and no note field — neither exists in the ledger,
 * and a control that collects something nothing stores is a lie told in the interface. There is no
 * generic cancel button either: undoing an abono happens from the customer's history as a
 * compensating movement, which is the only correction this system performs.
 *
 * The resulting-balance preview is arithmetic on `Long` cents shown before the fact; the actual
 * allocation is FIFO by oldest due date and belongs to the financial layer, untouched here. An
 * amount larger than the debt stays allowed and previews a negative balance, because a customer who
 * overpays really is in credit and the ledger records that instead of silently refusing it.
 */
@Composable
fun PaymentScreen(
    customers: List<CustomerBalanceRow>,
    modifier: Modifier = Modifier,
    initialCustomer: CustomerBalanceRow? = null,
    onCreatePayment: (CustomerBalanceRow, Long) -> Unit = { _, _ -> },
    /**
     * Bumped by the host when the bar is tapped on the tab already showing: back to the root of the
     * tab, which means the amount field empty and nobody selected. 0 means "never tapped", so the
     * first composition is not treated as a reset.
     */
    resetToken: Int = 0,
) {
    var customer by remember(initialCustomer?.customer?.id) {
        mutableStateOf(preselectedCustomer(initialCustomer, customers))
    }
    var amountText by remember { mutableStateOf("") }
    // Tapping Abonos while already on Abonos starts the form over instead of leaving the last
    // amount typed. Guarded on > 0 so the first composition keeps the preselected customer.
    LaunchedEffect(resetToken) {
        if (resetToken > 0) {
            customer = preselectedCustomer(initialCustomer, customers)
            amountText = ""
        }
    }
    val cents = pesosToCents(amountText)
    // Whoever owes most first, so the common case is one tap away.
    val ordered = customers.sortedByDescending { it.balanceCents }

    BoxWithConstraints(modifier = modifier.fillMaxSize().imePadding()) {
        val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
        LazyColumn(
            modifier = Modifier
                .widthIn(max = layout.contentMaxWidthDp.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .testTag(PAYMENT_LIST_TAG),
            contentPadding = PaddingValues(
                start = layout.horizontalPaddingDp.dp,
                end = layout.horizontalPaddingDp.dp,
                bottom = NavigationLayoutPolicy.bottomBarClearanceFor(
                    NavigationLayoutPolicy.modeFor(maxWidth, LocalDensity.current.fontScale),
                ),
            ),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
        ) {
            item {
                NeoTaskHeader(
                    title = stringResource(R.string.payment_title),
                )
            }
            item {
                // The raw format string, resolved here because optionDetail is a plain lambda and
                // cannot call stringResource itself.
                val balanceFormat = stringResource(R.string.new_sale_customer_balance)
                NeoSelectorField(
                    label = stringResource(R.string.new_sale_customer_label),
                    selected = customer,
                    placeholder = stringResource(R.string.new_sale_customer_placeholder),
                    options = ordered,
                    optionTitle = { it.customer.name },
                    optionDetail = { balanceFormat.format(Money.format(it.balanceCents)) },
                    emptyText = stringResource(R.string.new_sale_customer_empty),
                    onSelect = { customer = it },
                )
            }
            customer?.let { chosen ->
                item { BalancePanel(balanceCents = chosen.balanceCents, paymentCents = cents) }
            }
            item { MoneyField(stringResource(R.string.payment_amount_label), amountText) { amountText = it } }
            item {
                Text(
                    text = stringResource(R.string.payment_allocation_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                val chosen = customer
                NeoAction(
                    label = stringResource(R.string.payment_save),
                    onClick = {
                        if (chosen != null && cents != null) {
                            onCreatePayment(chosen, cents)
                            customer = null
                            amountText = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = chosen != null && cents != null,
                )
            }
        }
    }
}

@Composable
private fun BalancePanel(balanceCents: Long, paymentCents: Long?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = NeoShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(NeoSpacing.md)) {
            NeoAmountRow(
                label = stringResource(R.string.payment_current_balance),
                amountCents = balanceCents,
                valueModifier = Modifier.testTag(PAYMENT_BALANCE_TAG),
            )
            if (paymentCents != null) {
                Spacer(Modifier.height(NeoSpacing.sm))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(NeoSpacing.sm))
                val remaining = balanceCents - paymentCents
                NeoAmountRow(
                    label = stringResource(R.string.payment_resulting_balance),
                    amountCents = remaining,
                    valueModifier = Modifier.testTag(PAYMENT_RESULT_TAG),
                    // A settled or negative balance is money the customer no longer owes, which is
                    // the mint accent's one job. The label carries the meaning either way.
                    color = if (remaining <= 0L) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (remaining < 0L) {
                    Spacer(Modifier.height(NeoSpacing.xs))
                    Text(
                        text = stringResource(R.string.payment_credit_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
