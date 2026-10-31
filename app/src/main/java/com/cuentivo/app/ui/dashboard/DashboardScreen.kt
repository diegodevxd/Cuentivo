package com.cuentivo.app.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cuentivo.app.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.DashboardActivity
import com.cuentivo.app.domain.Money
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoEmptyState
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoMoney
import com.cuentivo.app.ui.NeoPanel
import com.cuentivo.app.ui.NeoSectionHeader
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NavigationLayoutPolicy
import com.cuentivo.app.ui.NeoSpacing
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * The compact rows the reference uses inside a section. Kept well under the generic panel height so
 * the three critical customers and five activity rows all stay above the fold at 320 dp.
 */
private val SECTION_ROW_HEIGHT = 60.dp

/** Compact, action-first home screen. All figures and rows come from DashboardUiState. */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    customers: List<CustomerBalanceRow>,
    modifier: Modifier = Modifier,
    onCreateBusiness: () -> Unit = {},
    /**
     * Retained as part of the screen's contract: the bottom bar owns "Nueva venta" permanently, so
     * this screen does not render it, but the host keeps passing the handler.
     */
    onNewSale: () -> Unit,
    onRegisterPayment: () -> Unit,
    onCustomer: (CustomerBalanceRow) -> Unit,
    onSeeAllCustomers: () -> Unit,
) {
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
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.md),
        ) {
            when (state) {
                DashboardUiState.NoBusiness -> item {
                    NeoEmptyState(
                        title = stringResource(R.string.app_welcome_title),
                        detail = stringResource(R.string.app_welcome_detail),
                        actionLabel = stringResource(R.string.app_create_business),
                        onAction = onCreateBusiness,
                    )
                }

                is DashboardUiState.Content -> dashboardContent(
                    state = state,
                    customers = customers,
                    onNewSale = onNewSale,
                    onRegisterPayment = onRegisterPayment,
                    onCustomer = onCustomer,
                    onSeeAllCustomers = onSeeAllCustomers,
                )
            }
        }
    }
}

private fun LazyListScope.dashboardContent(
    state: DashboardUiState.Content,
    customers: List<CustomerBalanceRow>,
    onNewSale: () -> Unit,
    onRegisterPayment: () -> Unit,
    onCustomer: (CustomerBalanceRow) -> Unit,
    onSeeAllCustomers: () -> Unit,
) {
    // One dominant receivable hero, then the two secondary month figures paired beneath it, then
    // the task-oriented sections. Moderate bento inside the mobile rail: hierarchy through size and
    // spacing, not a web-like grid.
    item {
        DashboardHero(state)
    }

    // The month, in the two numbers a shopkeeper actually asks about. Both were already being
    // computed and carried this far; until now nothing drew them.
    item { NeoSectionHeader(title = stringResource(R.string.dashboard_month_title)) }
    item {
        Card(
            modifier = Modifier.fillMaxWidth().testTag("dashboard-month"),
            shape = NeoShapes.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(NeoSpacing.md),
                verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    MonthFigure(
                        label = stringResource(R.string.dashboard_month_sold),
                        amountCents = state.soldCents,
                        modifier = Modifier.weight(1f),
                    )
                    MonthFigure(
                        label = stringResource(R.string.dashboard_month_collected),
                        amountCents = state.collectedCents,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // The payment action the dashboard owns. "Nueva venta" lives permanently in the bottom bar, so
    // this screen keeps only the action the bar does not offer as a dedicated control.
    item {
        NeoAction(
            stringResource(R.string.detail_register_payment),
            onRegisterPayment,
            Modifier.fillMaxWidth(),
        )
    }

    val today = LocalDate.now().toEpochDay()
    val critical = DashboardPresentation.criticalCustomers(customers, state.dueSales, today)
    item {
        NeoSectionHeader(
            title = stringResource(R.string.dashboard_critical_customers),
            trailing = {
                TextButton(
                    onClick = onSeeAllCustomers,
                    // Material's 24 dp horizontal padding pushes the action off the reference's
                    // right gutter. The 48 dp touch target is preserved by the button's own
                    // minimum height.
                    contentPadding = PaddingValues(horizontal = NeoSpacing.sm),
                ) {
                    Text(stringResource(R.string.dashboard_see_all), style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
    if (critical.isEmpty()) {
        // No action: "nobody is late" is the good outcome, and there is nothing to do about it.
        item {
            NeoEmptyState(
                title = stringResource(R.string.dashboard_no_urgent_title),
                detail = stringResource(R.string.dashboard_no_urgent_detail),
            )
        }
    } else {
        items(critical, key = { it.customer.customer.id }) { criticalCustomer ->
            CriticalCustomerRow(
                critical = criticalCustomer,
                status = criticalCustomerStatus(criticalCustomer, state, today),
                onClick = { onCustomer(criticalCustomer.customer) },
            )
        }
    }

    item { NeoSectionHeader(title = stringResource(R.string.dashboard_recent_activity)) }
    if (state.recentActivity.isEmpty()) {
        item {
            NeoEmptyState(
                title = stringResource(R.string.dashboard_no_activity_title),
                detail = stringResource(R.string.dashboard_no_activity_detail),
            )
        }
    } else {
        items(state.recentActivity.take(5), key = { it.id }) { activity -> ActivityRow(activity) }
    }
}

@Composable
private fun DashboardMetric(label: String, amountCents: Long, modifier: Modifier, isOverdue: Boolean = false) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NeoSpacing.xs))
        NeoMoney(
            amountCents = amountCents,
            color = if (isOverdue && amountCents > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The reference keeps `Vence hoy` and `Vencido` inside the receivable card rather than as a
 * separate strip below it, and carries no supporting sentence under the amount. Both details matter
 * for density: together they were costing about 55 dp and pushing recent activity off the screen.
 *
 * Only a minimum height is enforced: the hero grows with its content instead of capping it, so the
 * dominant metric can never be clipped at large text scales.
 */
@Composable
private fun DashboardHero(state: DashboardUiState.Content) {
    NeoPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 142.dp),
        accent = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = stringResource(R.string.dashboard_receivable_total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(NeoSpacing.xs))
        NeoMoney(
            amountCents = state.receivableCents,
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(NeoSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NeoSpacing.lg),
        ) {
            DashboardMetric(stringResource(R.string.dashboard_due_today), state.dueTodayCents, Modifier.weight(1f))
            DashboardMetric(stringResource(R.string.dashboard_overdue), state.overdueCents, Modifier.weight(1f), isOverdue = true)
        }
    }
}

/**
 * One compact row instead of a full section state. The reference never devotes a padded card with a
 * title and an explanation to an empty section, and doing so pushes recent activity off a 320 dp
 * screen.
 */

/**
 * The reference critical row: a leading urgency affordance, a one-line name, a one-line reason, and
 * the balance in the neutral money colour. The urgency is carried by the icon tile and the reason
 * line, not by recolouring the amount.
 */
@Composable
private fun CriticalCustomerRow(
    critical: CriticalCustomer,
    status: CriticalStatus,
    onClick: () -> Unit,
) {
    val overdue = critical.overdueCents > 0
    val accent = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    SectionRowSurface(
        border = if (overdue) accent.copy(alpha = 0.38f) else MaterialTheme.colorScheme.outlineVariant,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (overdue) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = NeoShapes.row,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (overdue) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp),
                )
            } else {
                // The initial is what every customer who is *not* overdue shows. Without it the
                // avatar is an empty box, which reads as a picture that failed to load rather than
                // as "nothing is wrong here".
                Text(
                    text = initialOf(critical.customer.customer.name),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = NeoSpacing.sm),
        ) {
            Text(
                text = critical.customer.customer.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (status.quantity != null) {
                    pluralStringResource(status.labelRes, status.quantity, status.quantity)
                } else {
                    stringResource(status.labelRes)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (status.urgent) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NeoMoney(
            amountCents = critical.customer.balanceCents,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ActivityRow(activity: DashboardActivity) {
    val visual = DashboardActivityPresentation.from(activity.type, activity.direction)
    // Use the same localized label as the customer ledger; activity labels are rendered text too.
    val label = if (activity.isPriorDebt) {
        stringResource(R.string.detail_movement_prior_debt)
    } else {
        visual.label
    }
    val accent = when (visual.accent) {
        DashboardActivityAccent.PAYMENT -> MaterialTheme.colorScheme.secondary
        DashboardActivityAccent.REFUND -> MaterialTheme.colorScheme.error
        DashboardActivityAccent.SALE, DashboardActivityAccent.NEUTRAL ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Only real cash movement is tinted; a sale keeps the neutral money colour of the reference.
    val amountColor = if (visual.sign.isEmpty()) MaterialTheme.colorScheme.onSurface else accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SECTION_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .size(7.dp)
                .background(accent, CircleShape),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = NeoSpacing.md),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.dashboard_activity_line, activity.customerName, activityTime(activity)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = visual.sign + Money.format(activity.amountCents),
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            color = amountColor,
            maxLines = 1,
        )
    }
}

/** The shared graphite row shell, fixed at the reference density for every section row. */
@Composable
private fun SectionRowSurface(
    border: Color,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = SECTION_ROW_HEIGHT)
    Card(
        modifier = if (onClick == null) base else base.clickable(onClick = onClick),
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = NeoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

private data class CriticalStatus(
    /** String resource for the reason label. */
    val labelRes: Int,
    val urgent: Boolean,
    /** Day count for the plural day-overdue label; null for plain-string labels. */
    val quantity: Int? = null,
)

/**
 * The reason a customer is critical, worded like the reference ("15 días vencido"). The day count
 * comes from the oldest unpaid due date actually present in `dueSales`; nothing is invented.
 */
private fun criticalCustomerStatus(
    critical: CriticalCustomer,
    state: DashboardUiState.Content,
    today: Long,
): CriticalStatus {
    val customerId = critical.customer.customer.id
    if (critical.overdueCents > 0) {
        val oldestDue = state.dueSales
            .filter { it.customerId == customerId && it.dueDateEpochDay < today }
            .minOfOrNull { it.dueDateEpochDay }
        val days = oldestDue?.let { today - it } ?: 0L
        return if (days >= 1L) {
            CriticalStatus(
                labelRes = R.plurals.dashboard_critical_days_overdue,
                urgent = true,
                quantity = days.toInt(),
            )
        } else {
            CriticalStatus(labelRes = R.string.dashboard_critical_overdue_balance, urgent = true)
        }
    }
    val dueToday = state.dueSales.any { it.customerId == customerId && it.dueDateEpochDay == today }
    return if (dueToday) {
        CriticalStatus(labelRes = R.string.dashboard_due_today, urgent = false)
    } else {
        CriticalStatus(labelRes = R.string.dashboard_critical_balance_pending, urgent = false)
    }
}

internal fun initialOf(name: String): String =
    name.trim().firstOrNull()?.uppercase() ?: "?"

private fun activityTime(activity: DashboardActivity): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, Locale.forLanguageTag("es-MX"))
        .format(Date(activity.occurredAtEpochMillis))

/** One figure of the month: the label small and quiet, the amount doing the talking. */
@Composable
private fun MonthFigure(label: String, amountCents: Long, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Money.format(amountCents),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
