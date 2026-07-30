package com.cuentivo.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.ProductImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.cuentivo.app.domain.AccessDecision
import com.cuentivo.app.domain.Alert
import com.cuentivo.app.domain.FeatureAccessManager
import com.cuentivo.app.domain.Insight
import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.SaleDraft
import com.cuentivo.app.domain.SaleDraftLine
import com.cuentivo.app.domain.SheetImport
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.dashboard.DashboardUiState

/**
 * A sale or payment the screen may offer to correct.
 *
 * Cancelling never deletes anything: it writes a compensating movement. The screen says so out
 * loud before confirming, because "cancelar" reads like "borrar" to the person using the app.
 */
data class CancelableItem(
    val id: String,
    val kind: Kind,
    val title: String,
    val detail: String,
    val amountCents: Long,
) {
    enum class Kind { SALE, PAYMENT }
}

/**
 * Shown while the device is being claimed for the account that just signed in.
 *
 * Brief on purpose, but it must exist: whatever renders here cannot query Room yet, because the
 * previous account's rows may still be on their way out.
 */
@Composable
fun CuentivoLoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Enough rejected rows to act on; a dialog listing two hundred of them helps nobody. */
private const val MAX_LISTED_REJECTIONS = 10

/**
 * A floating pill instead of a bar welded to the bottom edge.
 *
 * Every tab gets `weight(1f)`: with five destinations and labels as long as "Productos", letting
 * them size themselves overflows a 360 dp screen. Equal shares can't.
 *
 * The selected tab sits on a neon fill with dark text on top — the one place the fluorescent colour
 * is safe to use at full strength, because nothing has to be read *through* it.
 */
@Composable
private fun OvalNavigationBar(current: AppDestination, onSelect: (AppDestination) -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 16.dp,
        // The activity is edge-to-edge, so this sits under the system back/home/recents bar unless
        // it subtracts that inset itself. Material's NavigationBar did this for us; a hand-rolled
        // Surface does not, which is exactly how the two bars ended up on top of each other.
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppDestination.entries.forEach { item ->
                val selected = item == current
                val content = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else Color.Transparent
                        )
                        // 48 dp of height total: the floor for something used one-handed, walking.
                        .clickable { onSelect(item) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = content, modifier = Modifier.size(22.dp))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = content,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private enum class AppDestination(val label: String, val icon: ImageVector) {
    HOME("Inicio", Icons.Filled.Home),
    CUSTOMERS("Clientes", Icons.Filled.Person),
    NEW_SALE("Vender", Icons.Filled.ShoppingCart),
    PRODUCTS("Productos", Icons.Filled.Star),
    PAYMENTS("Abonos", Icons.Filled.CheckCircle),
}

@Composable
fun CuentivoApp(
    state: DashboardUiState,
    customers: List<CustomerBalanceRow> = emptyList(),
    products: List<ProductEntity> = emptyList(),
    tier: SubscriptionTier = SubscriptionTier.FREE,
    onCreateBusiness: () -> Unit = {},
    onCreateCustomer: (String, String?) -> Unit = { _, _ -> },
    onEditCustomer: (CustomerBalanceRow, String, String?) -> Unit = { _, _, _ -> },
    onArchiveCustomer: (CustomerBalanceRow) -> Unit = {},
    onCreateProduct: (String, Long, String?) -> Unit = { _, _, _ -> },
    onEditProduct: (ProductEntity, String, Long, String?) -> Unit = { _, _, _, _ -> },
    onArchiveProduct: (ProductEntity) -> Unit = {},
    onImportProducts: (Uri) -> Unit = {},
    importReport: SheetImport? = null,
    onDismissImportReport: () -> Unit = {},
    onShareImportTemplate: () -> Unit = {},
    onCreateSale: (CustomerBalanceRow, SaleDraft) -> Unit = { _, _ -> },
    onCreatePayment: (CustomerBalanceRow, Long) -> Unit = { _, _ -> },
    statementCustomer: String? = null,
    statement: List<StatementEntry> = emptyList(),
    cancelables: List<CancelableItem> = emptyList(),
    onCancelSale: (CancelableItem) -> Unit = {},
    onCancelPayment: (CancelableItem) -> Unit = {},
    onShowStatement: (CustomerBalanceRow) -> Unit = {},
    onDismissStatement: () -> Unit = {},
    onShareStatementCsv: () -> Unit = {},
    onShareStatementText: () -> Unit = {},
    onShareDebtorList: () -> Unit = {},
    alerts: List<Alert> = emptyList(),
    onOpenAlertCustomer: (String) -> Unit = {},
    blocked: AccessDecision.Blocked? = null,
    onDismissBlocked: () -> Unit = {},
    onUpgrade: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onDeleteAccount: () -> Unit = {},
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    var destination by remember { mutableStateOf(AppDestination.HOME) }
    var showPlans by remember { mutableStateOf(false) }
    // Hoisted out of the screens so the floating button can open the same form the screen owns.
    var showCustomerForm by remember { mutableStateOf(false) }
    var showProductForm by remember { mutableStateOf(false) }
    var showAlerts by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { OvalNavigationBar(destination) { destination = it } },
        floatingActionButton = {
            // Only where there is something to add. Vender and Abonos are already forms — a button
            // that opens the screen you are standing on is noise.
            val action: (() -> Unit)? = when (destination) {
                AppDestination.HOME -> { { destination = AppDestination.NEW_SALE } }
                AppDestination.CUSTOMERS -> { { showCustomerForm = true } }
                AppDestination.PRODUCTS -> { { showProductForm = true } }
                AppDestination.NEW_SALE, AppDestination.PAYMENTS -> null
            }
            action?.let {
                FloatingActionButton(
                    onClick = it,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = when (destination) {
                            AppDestination.HOME -> "Nueva venta"
                            AppDestination.CUSTOMERS -> "Agregar cliente"
                            else -> "Agregar producto"
                        },
                    )
                }
            }
        },
    ) { padding ->
        when (destination) {
            AppDestination.HOME -> DashboardScreen(
                state = state,
                customers = customers,
                padding = padding,
                onCreateBusiness = onCreateBusiness,
                onNewSale = { destination = AppDestination.NEW_SALE },
                onShowStatement = onShowStatement,
                onShareDebtorList = onShareDebtorList,
                onSignOut = onSignOut,
                onDeleteAccount = onDeleteAccount,
                onShowPlans = { showPlans = true },
                onShowAlerts = { showAlerts = true },
                alertCount = alerts.size,
                tier = tier,
            )
            AppDestination.CUSTOMERS -> CustomersScreen(customers, padding, showCustomerForm, { showCustomerForm = it }, onCreateCustomer, onEditCustomer, onArchiveCustomer, onShowStatement)
            AppDestination.PRODUCTS -> ProductsScreen(products, padding, showProductForm, { showProductForm = it }, onCreateProduct, onEditProduct, onArchiveProduct, onImportProducts, onShareImportTemplate)
            AppDestination.NEW_SALE -> NewSaleScreen(customers, products, padding, onCreateSale)
            AppDestination.PAYMENTS -> PaymentScreen(customers, padding, onCreatePayment)
        }
    }
    if (statementCustomer != null) {
        StatementSheet(
            customerName = statementCustomer,
            entries = statement,
            cancelables = cancelables,
            onCancelSale = onCancelSale,
            onCancelPayment = onCancelPayment,
            onShareCsv = onShareStatementCsv,
            onShareText = onShareStatementText,
            onDismiss = onDismissStatement,
        )
    }
    blocked?.let { limit ->
        AlertDialog(
            onDismissRequest = onDismissBlocked,
            title = { Text(limit.title) },
            text = { Text(limit.detail) },
            confirmButton = {
                TextButton(onClick = { onDismissBlocked(); showPlans = true }) { Text("Ver Cuentivo Pro") }
            },
            dismissButton = { TextButton(onClick = onDismissBlocked) { Text("Ahora no") } },
        )
    }
    if (showAlerts) {
        AlertsSheet(
            alerts = alerts,
            onOpenCustomer = onOpenAlertCustomer,
            onDismiss = { showAlerts = false },
        )
    }
    importReport?.let { report ->
        AlertDialog(
            onDismissRequest = onDismissImportReport,
            title = { Text("Importación terminada") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Se agregaron ${report.accepted.size} productos.")
                    if (report.rejected.isNotEmpty()) {
                        Text("Estos renglones se quedaron fuera:", style = MaterialTheme.typography.labelLarge)
                        // The line number is the point: it is what lets someone find the row in
                        // their own file and correct it.
                        report.rejected.take(MAX_LISTED_REJECTIONS).forEach { rejected ->
                            Text("Renglón ${rejected.lineNumber}: ${rejected.reason}", style = MaterialTheme.typography.labelLarge)
                        }
                        if (report.rejected.size > MAX_LISTED_REJECTIONS) {
                            Text("y ${report.rejected.size - MAX_LISTED_REJECTIONS} más.", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismissImportReport) { Text("Entendido") } },
        )
    }
    if (showPlans) {
        SubscriptionSheet(
            tier = tier,
            onSubscribe = { showPlans = false; onUpgrade() },
            onDismiss = { showPlans = false },
        )
    }
}

/**
 * The two plans, side by side, with the current one marked.
 *
 * Reads its limits from FeatureAccessManager rather than repeating them, so the screen can never
 * promise something different from what the app actually enforces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionSheet(
    tier: SubscriptionTier,
    onSubscribe: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Planes de Cuentivo", style = MaterialTheme.typography.headlineSmall)

            PlanCard(
                name = "Gratis",
                price = "Sin costo",
                isCurrent = tier == SubscriptionTier.FREE,
                features = listOf(
                    "Hasta ${FeatureAccessManager.FREE_CUSTOMER_LIMIT} clientes",
                    "Hasta ${FeatureAccessManager.FREE_SALE_LIMIT} ventas registradas",
                    "Ventas, abonos y estados de cuenta",
                    "Compartir la cuenta de un cliente",
                ),
                missing = listOf(
                    "Sin respaldo en la nube",
                    "Sin fotos de productos ni comprobantes",
                    "Sin importar/exportar Excel",
                    "Sin equipo ni roles",
                ),
            )

            PlanCard(
                name = "Cuentivo Pro",
                price = "$199 al mes",
                isCurrent = tier == SubscriptionTier.PRO,
                features = listOf(
                    "Clientes y ventas ilimitados",
                    "Respaldo y sincronización en la nube",
                    "Fotos de productos y comprobantes de pago",
                    "Importar y exportar productos en Excel",
                    "Compartir la lista completa de deudores",
                    "Hasta 5 integrantes con roles",
                    "Historial y auditoría completos",
                ),
                missing = emptyList(),
            )

            if (tier == SubscriptionTier.FREE) {
                FilledTonalButton(onClick = onSubscribe, modifier = Modifier.fillMaxWidth()) {
                    Text("Suscribirme a Pro")
                }
                Text(
                    "El cobro se hace por Google Play y puedes cancelarlo cuando quieras desde la " +
                        "tienda. Tus datos no se borran si cancelas.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Tu suscripción se administra desde Google Play.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlanCard(
    name: String,
    price: String,
    isCurrent: Boolean,
    features: List<String>,
    missing: List<String>,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            if (isCurrent) 2.dp else 1.dp,
            if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (isCurrent) {
                    AssistChip(onClick = {}, enabled = false, label = { Text("Tu plan") })
                }
            }
            Text(price, style = MaterialTheme.typography.headlineSmall)
            features.forEach { Text("✓  $it") }
            missing.forEach {
                Text("·  $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A customer's ledger, most recent last, with one-tap sharing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatementSheet(
    customerName: String,
    entries: List<StatementEntry>,
    cancelables: List<CancelableItem>,
    onCancelSale: (CancelableItem) -> Unit,
    onCancelPayment: (CancelableItem) -> Unit,
    onShareCsv: () -> Unit,
    onShareText: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingCancel by remember { mutableStateOf<CancelableItem?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NeoSectionTitle(customerName, eyebrow = "Estado de cuenta")
            Text(
                "Historial financiero",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entries.isEmpty()) Text("Este cliente aún no tiene movimientos")
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entries.forEach { entry ->
                    Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(statementTypeLabel(entry.type))
                                Text(entry.dateIso, style = MaterialTheme.typography.labelLarge)
                            }
                            Text(
                                (if (entry.direction == "DEBIT") "+" else "−") + formatMoney(entry.amountCents),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (entry.direction == "DEBIT") {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                },
                            )
                        }
                    }
                }
            }
            if (entries.isNotEmpty()) {
                NeoPrimaryButton("Compartir resumen", onShareText, Modifier.fillMaxWidth())
                OutlinedButton(onClick = onShareCsv, modifier = Modifier.fillMaxWidth()) {
                    Text("Compartir como archivo CSV")
                }
            }
            if (cancelables.isNotEmpty()) {
                Text("Corregir", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Nada se borra: se registra un movimiento que compensa el error y queda en el historial.",
                    style = MaterialTheme.typography.labelLarge,
                )
                cancelables.forEach { item ->
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title)
                                Text(item.detail, style = MaterialTheme.typography.labelLarge)
                            }
                            TextButton(onClick = { pendingCancel = item }) { Text("Cancelar") }
                        }
                    }
                }
            }
        }
    }
    pendingCancel?.let { item ->
        val isSale = item.kind == CancelableItem.Kind.SALE
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text(if (isSale) "¿Cancelar esta venta?" else "¿Cancelar este abono?") },
            text = {
                Text(
                    if (isSale) {
                        "${item.title} — se le quitarán ${formatMoney(item.amountCents)} de deuda a $customerName. " +
                            "La venta no se borra: queda cancelada y con su movimiento de corrección."
                    } else {
                        "${item.title} — la deuda de $customerName subirá ${formatMoney(item.amountCents)}, " +
                            "porque el abono deja de contar. El cobro original queda registrado."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isSale) onCancelSale(item) else onCancelPayment(item)
                    pendingCancel = null
                }) { Text("Sí, cancelar") }
            },
            dismissButton = { TextButton(onClick = { pendingCancel = null }) { Text("No") } },
        )
    }
}

private fun statementTypeLabel(type: String): String = when (type) {
    "CHARGE" -> "Cargo"
    "PAYMENT" -> "Abono"
    "ADJUSTMENT" -> "Ajuste"
    "REFUND" -> "Devolución"
    "CANCELLATION" -> "Cancelación"
    else -> type
}

/**
 * A tappable field that opens a searchable bottom sheet to pick one option.
 * This is the piece that lets the user actually choose a customer or product,
 * instead of being stuck with the first row of the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SelectorField(
    label: String,
    selected: T?,
    placeholder: String,
    options: List<T>,
    optionTitle: (T) -> String,
    optionDetail: (T) -> String?,
    emptyText: String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Card(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(
                    selected?.let(optionTitle) ?: placeholder,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Elegir $label")
        }
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(label, style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(query, { query = it }, label = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val filtered = options.filter { optionTitle(it).contains(query, ignoreCase = true) }
                if (filtered.isEmpty()) Text(if (options.isEmpty()) emptyText else "No se encontró nada con ese nombre")
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filtered.forEach { option ->
                        Card(onClick = { onSelect(option); open = false }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(optionTitle(option))
                                optionDetail(option)?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Amount entry in pesos ("150.50"), converted to integer cents only at submit time.
 * Delegates to Money so the screens, the share texts and the CSV all agree. No Double anywhere.
 */
internal fun pesosToCents(text: String): Long? = Money.positiveCentsFromPesos(text)

@Composable
private fun MoneyField(label: String, text: String, onTextChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = { raw -> onTextChange(raw.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        prefix = { Text("$ ") },
        supportingText = {
            val cents = pesosToCents(text)
            when {
                text.isBlank() -> Text("Escribe el monto en pesos, por ejemplo 150.50")
                cents == null -> Text("Monto no válido", color = MaterialTheme.colorScheme.error)
                else -> Text("Se registrará ${formatMoney(cents)}")
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FormDialog(
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
        confirmButton = { TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/**
 * Sale composition: pick a customer, add as many products as needed, then save.
 *
 * The cart is a domain SaleDraft rather than loose state, so the totals shown here are the same
 * integers that get written to the ledger.
 */
@Composable
private fun NewSaleScreen(
    customers: List<CustomerBalanceRow>,
    products: List<ProductEntity>,
    padding: PaddingValues,
    onCreateSale: (CustomerBalanceRow, SaleDraft) -> Unit,
) {
    var customer by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    var draft by remember { mutableStateOf(SaleDraft()) }
    var picked by remember { mutableStateOf<ProductEntity?>(null) }
    var quantityText by remember { mutableStateOf("1") }
    var discountText by remember { mutableStateOf("") }
    var dueInDays by remember { mutableStateOf<Long?>(null) }

    NeoResponsiveColumn(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        NeoSectionTitle("Nueva venta", eyebrow = "Operación")
        SelectorField(
            label = "Cliente",
            selected = customer,
            placeholder = "Toca para elegir al cliente",
            options = customers,
            optionTitle = { it.customer.name },
            optionDetail = { "Saldo: ${formatMoney(it.balanceCents)}" },
            emptyText = "Primero agrega un cliente en la pestaña Clientes",
            onSelect = { customer = it },
        )
        SelectorField(
            label = "Producto",
            selected = picked,
            placeholder = "Toca para elegir el producto",
            options = products,
            optionTitle = { it.name },
            optionDetail = { formatMoney(it.salePriceCents) },
            emptyText = "Primero agrega un producto en la pestaña Productos",
            onSelect = { picked = it },
        )
        OutlinedTextField(
            value = quantityText,
            onValueChange = { raw -> quantityText = raw.filter { it.isDigit() }.take(4) },
            label = { Text("Cantidad") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val quantity = quantityText.toIntOrNull() ?: 0
        val chosenProduct = picked
        NeoPrimaryButton(
            label = "Agregar al carrito",
            enabled = chosenProduct != null && quantity > 0,
            onClick = {
                if (chosenProduct != null && quantity > 0) {
                    draft = draft.withLine(
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
        )

        if (draft.isEmpty) {
            Text("El carrito está vacío. Elige un producto y agrégalo.")
        } else {
            Text("Productos de esta venta", style = MaterialTheme.typography.titleMedium)
            draft.lines.forEach { line ->
                Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${line.quantity} × ${line.productName}")
                            Text(formatMoney(line.unitPriceCents) + " c/u", style = MaterialTheme.typography.labelLarge)
                        }
                        Text(formatMoney(line.subtotalCents), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { draft = draft.withoutProduct(line.productId) }) { Text("Quitar") }
                    }
                }
            }
            MoneyField("Descuento de la venta (opcional)", discountText) { discountText = it }
            val discountCents = if (discountText.isBlank()) 0L else (Money.centsFromPesos(discountText) ?: 0L)
            draft = draft.copy(discountCents = discountCents)

            Text("¿Cuándo debe pagar?", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf<Pair<String, Long?>>("Sin fecha" to null, "7 días" to 7L, "15 días" to 15L, "30 días" to 30L)
                    .chunked(2)
                    .forEach { options ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { (label, days) ->
                                FilterChip(
                                    selected = dueInDays == days,
                                    onClick = { dueInDays = days },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
            }

            Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Total de la venta", style = MaterialTheme.typography.labelLarge)
                    Text(formatMoney(draft.totalCents), style = MaterialTheme.typography.headlineSmall)
                    if (discountCents > 0) {
                        Text("Antes del descuento: ${formatMoney(draft.subtotalCents)}")
                    }
                }
            }
        }

        val chosenCustomer = customer
        val error = draft.validationError()
        if (chosenCustomer == null && !draft.isEmpty) Text("Falta elegir al cliente")
        NeoPrimaryButton(
            label = "Registrar venta",
            enabled = chosenCustomer != null && error == null,
            onClick = {
                if (chosenCustomer != null && error == null) {
                    val due = dueInDays?.let { java.time.LocalDate.now().plusDays(it).toEpochDay() }
                    onCreateSale(chosenCustomer, draft.copy(dueDateEpochDay = due))
                    customer = null
                    draft = SaleDraft()
                    discountText = ""
                    dueInDays = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PaymentScreen(
    customers: List<CustomerBalanceRow>,
    padding: PaddingValues,
    onCreatePayment: (CustomerBalanceRow, Long) -> Unit,
) {
    var customer by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    var amountText by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Registrar abono", style = MaterialTheme.typography.headlineSmall)
        SelectorField(
            label = "Cliente",
            selected = customer,
            placeholder = "Toca para elegir al cliente",
            // Customers who owe something first, so the common case is one tap away.
            options = customers.sortedByDescending { it.balanceCents },
            optionTitle = { it.customer.name },
            optionDetail = { "Saldo: ${formatMoney(it.balanceCents)}" },
            emptyText = "Primero agrega un cliente en la pestaña Clientes",
            onSelect = { customer = it },
        )
        val cents = pesosToCents(amountText)
        customer?.let { chosen ->
            Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Saldo actual: ${formatMoney(chosen.balanceCents)}", style = MaterialTheme.typography.titleMedium)
                    if (cents != null) {
                        Text("Quedará: ${formatMoney(chosen.balanceCents - cents)}")
                    }
                }
            }
        }
        MoneyField("Monto del abono", amountText) { amountText = it }
        Text(
            "El abono se aplica primero a la venta más antigua por vencer.",
            style = MaterialTheme.typography.labelLarge,
        )
        FilledTonalButton(
            enabled = customer != null && cents != null,
            onClick = {
                val chosen = customer
                if (chosen != null && cents != null) {
                    onCreatePayment(chosen, cents)
                    customer = null
                    amountText = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Guardar abono") }
    }
}

/**
 * A product photo, or a placeholder when there is none — and when the file is simply not here.
 *
 * A product adopted from another device carries a file name only its author's phone has, so a
 * missing file is expected, not an error worth shouting about.
 */
@Composable
private fun ProductPhoto(
    fileName: String?,
    store: ProductImageStore,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // Decoding off the main thread: a list of products would otherwise stutter on a cheap phone.
    val bitmap by produceState<Bitmap?>(initialValue = null, fileName) {
        value = fileName?.let { name -> withContext(Dispatchers.IO) { store.load(name) } }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Foto del producto",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Icon(
            Icons.Filled.Star,
            contentDescription = "Sin foto",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The tappable photo box inside the new/edit product dialogs. */
@Composable
private fun ProductPhotoPicker(
    fileName: String?,
    store: ProductImageStore,
    targetId: String,
    onPicked: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    // PickVisualMedia opens the system photo picker, which needs no storage permission at all.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            // A camera photo is several megapixels; decoding it on the main thread freezes the
            // dialog long enough to look broken.
            val saved = withContext(Dispatchers.IO) {
                store.save(uri, targetId)?.also { fileName?.let(store::delete) }
            }
            working = false
            if (saved != null) onPicked(saved)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProductPhoto(
            fileName = fileName,
            store = store,
            size = 96.dp,
            modifier = Modifier.clickable {
                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
        Spacer(Modifier.width(12.dp))
        Column {
            TextButton(
                onClick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !working,
            ) { Text(if (working) "Guardando…" else if (fileName == null) "Agregar foto" else "Cambiar foto") }
            if (fileName != null) {
                TextButton(onClick = { store.delete(fileName); onPicked(null) }) {
                    Text("Quitar foto", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ProductsScreen(
    products: List<ProductEntity>,
    padding: PaddingValues,
    showForm: Boolean,
    onShowFormChange: (Boolean) -> Unit,
    onCreateProduct: (String, Long, String?) -> Unit,
    onEditProduct: (ProductEntity, String, Long, String?) -> Unit,
    onArchiveProduct: (ProductEntity) -> Unit,
    onImportProducts: (Uri) -> Unit,
    onShareImportTemplate: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ProductEntity?>(null) }
    var archiving by remember { mutableStateOf<ProductEntity?>(null) }
    val context = LocalContext.current
    val imageStore = remember(context) { ProductImageStore(context) }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Productos", style = MaterialTheme.typography.headlineSmall)
        // Wide-open MIME list: phones and cloud drives label .csv and .xlsx inconsistently, and a
        // strict filter greys out the very file the person came to pick.
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(onImportProducts)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
            ) { Text("Importar de Excel") }
            OutlinedButton(onClick = onShareImportTemplate, modifier = Modifier.weight(1f)) { Text("Plantilla") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Buscar por nombre o SKU") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val filtered = products.filter { it.name.contains(query, true) || (it.sku?.contains(query, true) == true) }
        if (filtered.isEmpty()) Text(if (query.isBlank()) "Aún no tienes productos registrados" else "No se encontraron productos")
        filtered.forEach { product ->
            Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProductPhoto(product.imageUri, imageStore, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(product.name)
                        Text(formatMoney(product.salePriceCents))
                        product.category?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                    }
                    TextButton(onClick = { editing = product }) { Text("Editar") }
                    TextButton(onClick = { archiving = product }) {
                        Text("Eliminar", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (showForm) {
        var name by remember { mutableStateOf("") }
        var priceText by remember { mutableStateOf("") }
        var photo by remember { mutableStateOf<String?>(null) }
        // The photo is picked before the product exists, so it is filed under an id minted here.
        // ProductEntity.imageUri stores the file name, not the id, so the two never have to match.
        val draftId = remember { UUID.randomUUID().toString() }
        val cents = pesosToCents(priceText)
        FormDialog(
            title = "Nuevo producto",
            confirmEnabled = name.isNotBlank() && cents != null,
            onConfirm = {
                if (name.isNotBlank() && cents != null) {
                    onCreateProduct(name.trim(), cents, photo)
                    onShowFormChange(false)
                }
            },
            onDismiss = { onShowFormChange(false) },
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre del producto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            MoneyField("Precio", priceText) { priceText = it }
            ProductPhotoPicker(photo, imageStore, draftId) { photo = it }
        }
    }
    editing?.let { product ->
        // Keyed on the product so reopening the dialog for another row starts from that row's data.
        var name by remember(product.id) { mutableStateOf(product.name) }
        var priceText by remember(product.id) { mutableStateOf(Money.pesosText(product.salePriceCents)) }
        var photo by remember(product.id) { mutableStateOf(product.imageUri) }
        val cents = pesosToCents(priceText)
        FormDialog(
            title = "Editar producto",
            confirmEnabled = name.isNotBlank() && cents != null,
            onConfirm = {
                if (name.isNotBlank() && cents != null) {
                    onEditProduct(product, name.trim(), cents, photo)
                    editing = null
                }
            },
            onDismiss = { editing = null },
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre del producto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            MoneyField("Precio", priceText) { priceText = it }
            ProductPhotoPicker(photo, imageStore, product.id) { photo = it }
            Text(
                "El precio nuevo solo aplica a ventas futuras: cada venta guarda el precio que se cobró ese día.",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
    archiving?.let { product ->
        AlertDialog(
            onDismissRequest = { archiving = null },
            title = { Text("¿Eliminar ${product.name}?") },
            text = {
                // True because sale_items keeps the name and price charged that day, so removing the
                // product from the catalog cannot rewrite what anyone was charged.
                Text(
                    "Dejará de aparecer al registrar ventas. Tus ventas anteriores no cambian: cada " +
                        "una guarda el nombre y el precio que cobraste ese día."
                )
            },
            confirmButton = {
                TextButton(onClick = { archiving = null; onArchiveProduct(product) }) {
                    Text("Sí, eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { archiving = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun CustomersScreen(
    customers: List<CustomerBalanceRow>,
    padding: PaddingValues,
    showForm: Boolean,
    onShowFormChange: (Boolean) -> Unit,
    onCreateCustomer: (String, String?) -> Unit,
    onEditCustomer: (CustomerBalanceRow, String, String?) -> Unit,
    onArchiveCustomer: (CustomerBalanceRow) -> Unit,
    onShowStatement: (CustomerBalanceRow) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    var archiving by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    NeoResponsiveColumn(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { layout ->
        Spacer(Modifier.height(8.dp))
        NeoSectionTitle("Clientes", eyebrow = "Directorio")
        OutlinedTextField(query, { query = it }, label = { Text("Buscar por nombre o teléfono") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val filtered = customers.filter { it.customer.name.contains(query, true) || (it.customer.phone?.contains(query) == true) }
        if (filtered.isEmpty()) Text(if (query.isBlank()) "Aún no tienes clientes registrados" else "No se encontraron clientes")
        else Text("Toca un cliente para ver su estado de cuenta", style = MaterialTheme.typography.labelLarge)
        filtered.forEach { row ->
            Card(
                onClick = { onShowStatement(row) },
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (layout.stackPrimaryActions) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(row.customer.name, style = MaterialTheme.typography.titleMedium)
                        Text(formatMoney(row.balanceCents), color = MaterialTheme.colorScheme.primary)
                        row.customer.phone?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { editing = row }) { Text("Editar") }
                            TextButton(onClick = { archiving = row }) {
                                Text("Eliminar", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.customer.name, style = MaterialTheme.typography.titleMedium)
                            Text(formatMoney(row.balanceCents), color = MaterialTheme.colorScheme.primary)
                            row.customer.phone?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                        }
                        TextButton(onClick = { editing = row }) { Text("Editar") }
                        TextButton(onClick = { archiving = row }) {
                            Text("Eliminar", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (showForm) {
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        FormDialog(
            title = "Nuevo cliente",
            confirmEnabled = name.isNotBlank(),
            onConfirm = {
                if (name.isNotBlank()) {
                    onCreateCustomer(name.trim(), phone.trim().ifBlank { null })
                    onShowFormChange(false)
                }
            },
            onDismiss = { onShowFormChange(false) },
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre del cliente") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = phone,
                onValueChange = { raw -> phone = raw.filter { it.isDigit() || it == '+' }.take(15) },
                label = { Text("Teléfono (opcional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    editing?.let { row ->
        // Keyed on the customer so reopening for another row starts from that row's data.
        var name by remember(row.customer.id) { mutableStateOf(row.customer.name) }
        var phone by remember(row.customer.id) { mutableStateOf(row.customer.phone.orEmpty()) }
        FormDialog(
            title = "Editar cliente",
            confirmEnabled = name.isNotBlank(),
            onConfirm = {
                if (name.isNotBlank()) {
                    onEditCustomer(row, name.trim(), phone.trim().ifBlank { null })
                    editing = null
                }
            },
            onDismiss = { editing = null },
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre del cliente") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = phone,
                onValueChange = { raw -> phone = raw.filter { it.isDigit() || it == '+' }.take(15) },
                label = { Text("Teléfono (opcional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Corregir el nombre no cambia sus ventas ni su saldo.",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
    archiving?.let { row ->
        // Balance comes from the ledger, so this is the same number the Inicio screen adds up.
        // Archiving someone who still owes would quietly shrink "Por cobrar" with nobody paying.
        val settled = row.balanceCents == 0L
        AlertDialog(
            onDismissRequest = { archiving = null },
            title = { Text(if (settled) "¿Eliminar a ${row.customer.name}?" else "${row.customer.name} tiene saldo") },
            text = {
                Text(
                    if (settled) {
                        "Dejará de aparecer en tus listas. Su historial de ventas y abonos se conserva."
                    } else {
                        "No se puede eliminar mientras su saldo no esté en cero: hoy es " +
                            "${formatMoney(row.balanceCents)}. Cobra lo que falta o cancela sus " +
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
                    TextButton(onClick = { archiving = null; onShowStatement(row) }) { Text("Ver estado de cuenta") }
                }
            },
            dismissButton = { TextButton(onClick = { archiving = null }) { Text("Cancelar") } },
        )
    }
}

private enum class DebtorFilter(val label: String) {
    OWING("Con deuda"),
    ALL("Todos"),
}

/**
 * Home: the money totals, then who actually owes it.
 *
 * The list is the point — a dashboard of four numbers tells the owner nothing about who to call
 * today.
 */
@Composable
private fun DashboardScreen(
    state: DashboardUiState,
    customers: List<CustomerBalanceRow>,
    padding: PaddingValues,
    onCreateBusiness: () -> Unit,
    onNewSale: () -> Unit,
    onShowStatement: (CustomerBalanceRow) -> Unit,
    onShareDebtorList: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onShowPlans: () -> Unit,
    onShowAlerts: () -> Unit,
    alertCount: Int,
    tier: SubscriptionTier,
) {
    var filter by remember { mutableStateOf(DebtorFilter.OWING) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    NeoResponsiveColumn(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
    ) { layout ->
        Spacer(Modifier.height(8.dp))
        when (state) {
            DashboardUiState.NoBusiness -> {
                Text("Bienvenido a Cuentivo", style = MaterialTheme.typography.headlineSmall)
                Text("Crea tu primer negocio para comenzar")
                FilledTonalButton(onClick = onCreateBusiness, modifier = Modifier.fillMaxWidth()) { Text("Crear mi negocio") }
            }
            is DashboardUiState.Content -> {
                // No "Nueva venta" button here any more: the floating button does it, and two ways
                // to start the same thing on one screen is clutter.
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.businessName,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    // The count is on the chip itself rather than a floating badge: a number nobody
                    // can read is just a red dot, and this has to work at arm's length.
                    AssistChip(
                        onClick = onShowAlerts,
                        label = { Text(if (alertCount > 0) "Avisos ($alertCount)" else "Avisos") },
                    )
                }
                NeoBalanceHero(
                    label = "Por cobrar",
                    amountCents = state.receivableCents,
                    supportingText = if (state.overdueCents > 0) {
                        "${formatMoney(state.overdueCents)} requieren atención"
                    } else {
                        "Tu cartera está al día"
                    },
                )
                state.insights.forEach { InsightCard(it) }
                if (layout.stackPrimaryActions) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeoMoneyPill("Vendido este mes", state.soldCents, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
                        NeoMoneyPill("Cobrado este mes", state.collectedCents, MaterialTheme.colorScheme.secondary, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        NeoMoneyPill("Vendido este mes", state.soldCents, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                        NeoMoneyPill("Cobrado este mes", state.collectedCents, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                    }
                }
                if (state.overdueCents > 0) {
                    Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Pagos vencidos", style = MaterialTheme.typography.labelLarge)
                            Text(
                                formatMoney(state.overdueCents),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                NeoSectionTitle(
                    title = "Quién me debe",
                    eyebrow = "Cartera",
                    trailing = {
                        AssistChip(onClick = onShareDebtorList, label = { Text("Compartir") })
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DebtorFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                val listed = when (filter) {
                    DebtorFilter.OWING -> customers.filter { it.balanceCents > 0 }
                    DebtorFilter.ALL -> customers
                }.sortedByDescending { it.balanceCents }
                if (listed.isEmpty()) {
                    Text(
                        if (customers.isEmpty()) "Agrega tu primer cliente en la pestaña Clientes"
                        else "Nadie tiene saldo pendiente. Todo cobrado."
                    )
                }
                listed.forEach { row ->
                    Card(
                onClick = { onShowStatement(row) },
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                        if (layout.stackLedgerAmounts) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(row.customer.name, style = MaterialTheme.typography.titleMedium)
                                row.customer.phone?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                                Spacer(Modifier.height(8.dp))
                                Text(formatMoney(row.balanceCents), style = MaterialTheme.typography.titleMedium)
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.customer.name, style = MaterialTheme.typography.titleMedium)
                                    row.customer.phone?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                                }
                                Text(
                                    formatMoney(row.balanceCents),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (row.balanceCents > 0) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                NeoSectionTitle("Tu cuenta", eyebrow = "Plan")
                Card(
                    onClick = onShowPlans,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (tier == SubscriptionTier.PRO) "Cuentivo Pro" else "Plan Gratis",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (tier == SubscriptionTier.PRO) {
                                "Clientes y ventas ilimitados. Toca para ver tu plan."
                            } else {
                                "Hasta ${FeatureAccessManager.FREE_CUSTOMER_LIMIT} clientes y " +
                                    "${FeatureAccessManager.FREE_SALE_LIMIT} ventas. Toca para ver Pro."
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
                // Google Play requires account deletion to be reachable from inside the app.
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Eliminar mi cuenta", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("¿Cerrar sesión?") },
            text = {
                // Said plainly because it is true: the database belongs to one account at a time, so
                // the next person to sign in on this phone clears whatever has not reached the cloud.
                Text(
                    "Tu información seguirá aquí si vuelves a entrar con esta misma cuenta. Pero si " +
                        "alguien más inicia sesión en este teléfono, lo que todavía no se haya " +
                        "sincronizado se borrará."
                )
            },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Cerrar sesión") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancelar") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar tu cuenta?") },
            text = {
                Text(
                    "Se borrará tu cuenta y toda la información guardada en este teléfono: clientes, " +
                        "ventas y abonos. Esta acción no se puede deshacer."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteAccount() }) {
                    Text("Sí, eliminar todo", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

/**
 * How the business is actually doing, in one sentence.
 *
 * The tone comes from the number, not from a wish to be encouraging: a bad month is tinted like bad
 * news. And it never leans on colour alone — the accent bar on the left plus the wording carry the
 * meaning, because at arm's length in the sun a coloured tint is the first thing to disappear.
 */
@Composable
private fun InsightCard(insight: Insight) {
    val accent = when (insight.tone) {
        Insight.Tone.GOOD -> MaterialTheme.colorScheme.primary
        Insight.Tone.BAD -> MaterialTheme.colorScheme.error
        Insight.Tone.NEUTRAL -> MaterialTheme.colorScheme.secondary
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(5.dp).height(56.dp).background(accent))
            Text(
                insight.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, cents: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(formatMoney(cents), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

private fun formatMoney(cents: Long): String = Money.format(cents)
