package com.cuentivo.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.DashboardActivity
import com.cuentivo.app.domain.Money
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoDirectoryRow
import com.cuentivo.app.ui.NeoEmptyState
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoMoney
import com.cuentivo.app.ui.NeoPanel
import com.cuentivo.app.ui.NeoSectionHeader
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing
import java.time.LocalDate
import java.util.Date
import java.text.DateFormat
import java.util.Locale

/** Compact, action-first home screen. All figures and rows come from DashboardUiState. */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    customers: List<CustomerBalanceRow>,
    modifier: Modifier = Modifier,
    onCreateBusiness: () -> Unit = {},
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
                bottom = NeoSpacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.md),
        ) {
            when (state) {
                DashboardUiState.NoBusiness -> item {
                    NeoEmptyState(
                        title = "Bienvenido a Cuentivo",
                        detail = "Crea tu primer negocio para comenzar",
                        actionLabel = "Crear mi negocio",
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

private fun androidx.compose.foundation.lazy.LazyListScope.dashboardContent(
    state: DashboardUiState.Content,
    customers: List<CustomerBalanceRow>,
    onNewSale: () -> Unit,
    onRegisterPayment: () -> Unit,
    onCustomer: (CustomerBalanceRow) -> Unit,
    onSeeAllCustomers: () -> Unit,
) {
    item {
        DashboardHero(state.receivableCents)
    }
    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NeoSpacing.lg),
        ) {
            DashboardMetric("Vence hoy", state.dueTodayCents, Modifier.weight(1f))
            DashboardMetric("Vencido", state.overdueCents, Modifier.weight(1f), isOverdue = true)
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm)) {
            NeoAction("Registrar abono", onRegisterPayment, Modifier.fillMaxWidth())
            NeoAction("Nueva venta", onNewSale, Modifier.fillMaxWidth(), primary = false)
        }
    }

    val today = LocalDate.now().toEpochDay()
    val critical = DashboardPresentation.criticalCustomers(customers, state.dueSales, today)
    item {
        NeoSectionHeader(
            title = "Clientes críticos",
            trailing = { TextButton(onClick = onSeeAllCustomers) { Text("Ver todos") } },
        )
    }
    if (critical.isEmpty()) {
        item {
            NeoEmptyState(
                title = "Sin cobros urgentes",
                detail = "Los saldos vencidos aparecerán aquí.",
            )
        }
    } else {
        items(critical, key = { it.customer.customer.id }) { criticalCustomer ->
            val status = criticalCustomerStatus(criticalCustomer.customer, state, today, criticalCustomer.overdueCents)
            NeoDirectoryRow(
                title = criticalCustomer.customer.customer.name,
                subtitle = status,
                amountCents = criticalCustomer.customer.balanceCents,
                status = if (criticalCustomer.overdueCents > 0) "Requiere atención" else null,
                accent = if (criticalCustomer.overdueCents > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                onClick = { onCustomer(criticalCustomer.customer) },
            )
        }
    }

    item { NeoSectionHeader(title = "Actividad reciente") }
    if (state.recentActivity.isEmpty()) {
        item {
            NeoEmptyState(
                title = "Aún no hay movimientos",
                detail = "Tus ventas y abonos aparecerán aquí.",
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

@Composable
private fun DashboardHero(amountCents: Long) {
    NeoPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 126.dp, max = 142.dp),
        accent = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = "Saldo total por cobrar",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(NeoSpacing.xs))
        NeoMoney(
            amountCents = amountCents,
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(NeoSpacing.sm))
        Text(
            text = "En cuentas por cobrar",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun criticalCustomerStatus(
    customer: CustomerBalanceRow,
    state: DashboardUiState.Content,
    today: Long,
    overdueCents: Long,
): String = when {
    overdueCents > 0 -> "Saldo vencido"
    state.dueSales.any { it.customerId == customer.customer.id && it.dueDateEpochDay == today } -> "Vence hoy"
    else -> "Saldo pendiente"
}

@Composable
private fun ActivityRow(activity: DashboardActivity) {
    val isPayment = activity.type == "PAYMENT"
    val amountColor = if (isPayment) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val sign = if (isPayment) "+" else ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .width(6.dp)
                .height(6.dp)
                .background(if (isPayment) MaterialTheme.colorScheme.primary else Color(0xFF9B7BFF), NeoShapes.row),
        )
        Column(Modifier.weight(1f).padding(start = NeoSpacing.sm)) {
            Text(activityTitle(activity), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                "${activity.customerName} · ${activityTime(activity)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = sign + Money.format(activity.amountCents),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
            color = amountColor,
        )
    }
}

private fun activityTitle(activity: DashboardActivity): String = when (activity.type) {
    "PAYMENT" -> "Abono recibido"
    "SALE" -> "Nueva venta"
    "ADJUSTMENT" -> "Ajuste registrado"
    "CANCELLATION" -> "Movimiento cancelado"
    else -> "Movimiento registrado"
}

private fun activityTime(activity: DashboardActivity): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, Locale.forLanguageTag("es-MX")).format(Date(activity.occurredAtEpochMillis))
