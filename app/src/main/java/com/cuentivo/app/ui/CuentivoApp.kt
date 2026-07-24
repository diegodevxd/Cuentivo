package com.cuentivo.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.domain.AccessDecision
import com.cuentivo.app.domain.FeatureAccessManager
import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.SaleDraft
import com.cuentivo.app.domain.SaleDraftLine
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.dashboard.DashboardUiState

private enum class AppDestination(val label: String, val icon: ImageVector) {
    HOME("Inicio", Icons.Filled.Home),
    CUSTOMERS("Clientes", Icons.Filled.Person),
    NEW_SALE("Vender", Icons.Filled.ShoppingCart),
    PAYMENTS("Abonos", Icons.Filled.CheckCircle),
    PRODUCTS("Productos", Icons.Filled.Star),
}

@Composable
fun CuentivoApp(
    state: DashboardUiState,
    customers: List<CustomerBalanceRow> = emptyList(),
    products: List<ProductEntity> = emptyList(),
    tier: SubscriptionTier = SubscriptionTier.FREE,
    onCreateBusiness: () -> Unit = {},
    onCreateCustomer: (String, String?) -> Unit = { _, _ -> },
    onCreateProduct: (String, Long) -> Unit = { _, _ -> },
    onCreateSale: (CustomerBalanceRow, SaleDraft) -> Unit = { _, _ -> },
    onCreatePayment: (CustomerBalanceRow, Long) -> Unit = { _, _ -> },
    statementCustomer: String? = null,
    statement: List<StatementEntry> = emptyList(),
    onShowStatement: (CustomerBalanceRow) -> Unit = {},
    onDismissStatement: () -> Unit = {},
    onShareStatementCsv: () -> Unit = {},
    onShareStatementText: () -> Unit = {},
    onShareDebtorList: () -> Unit = {},
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
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
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
                tier = tier,
            )
            AppDestination.CUSTOMERS -> CustomersScreen(customers, padding, onCreateCustomer, onShowStatement)
            AppDestination.PRODUCTS -> ProductsScreen(products, padding, onCreateProduct)
            AppDestination.NEW_SALE -> NewSaleScreen(customers, products, padding, onCreateSale)
            AppDestination.PAYMENTS -> PaymentScreen(customers, padding, onCreatePayment)
        }
    }
    if (statementCustomer != null) {
        StatementSheet(statementCustomer, statement, onShareStatementCsv, onShareStatementText, onDismissStatement)
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
    onShareCsv: () -> Unit,
    onShareText: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Estado de cuenta", style = MaterialTheme.typography.headlineSmall)
            Text(customerName, style = MaterialTheme.typography.titleMedium)
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
                            )
                        }
                    }
                }
            }
            if (entries.isNotEmpty()) {
                FilledTonalButton(onClick = onShareText, modifier = Modifier.fillMaxWidth()) {
                    Text("Enviar por WhatsApp")
                }
                OutlinedButton(onClick = onShareCsv, modifier = Modifier.fillMaxWidth()) {
                    Text("Compartir como archivo CSV")
                }
            }
        }
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
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content) },
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

    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nueva venta", style = MaterialTheme.typography.headlineSmall)
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
        FilledTonalButton(
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
        ) { Text("Agregar al carrito") }

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<Pair<String, Long?>>("Sin fecha" to null, "7 días" to 7L, "15 días" to 15L, "30 días" to 30L)
                    .forEach { (label, days) ->
                        FilterChip(
                            selected = dueInDays == days,
                            onClick = { dueInDays = days },
                            label = { Text(label) },
                        )
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
        FilledTonalButton(
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
        ) { Text("Registrar venta") }
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

@Composable
private fun ProductsScreen(products: List<ProductEntity>, padding: PaddingValues, onCreateProduct: (String, Long) -> Unit) {
    var query by remember { mutableStateOf("") }
    var showForm by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Productos", style = MaterialTheme.typography.headlineSmall)
        FilledTonalButton(onClick = { showForm = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Agregar producto") }
        OutlinedTextField(query, { query = it }, label = { Text("Buscar por nombre o SKU") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val filtered = products.filter { it.name.contains(query, true) || (it.sku?.contains(query, true) == true) }
        if (filtered.isEmpty()) Text(if (query.isBlank()) "Aún no tienes productos registrados" else "No se encontraron productos")
        filtered.forEach { product ->
            Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
                Column(Modifier.padding(16.dp)) {
                    Text(product.name)
                    Text(formatMoney(product.salePriceCents))
                    product.category?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
    if (showForm) {
        var name by remember { mutableStateOf("") }
        var priceText by remember { mutableStateOf("") }
        val cents = pesosToCents(priceText)
        FormDialog(
            title = "Nuevo producto",
            confirmEnabled = name.isNotBlank() && cents != null,
            onConfirm = {
                if (name.isNotBlank() && cents != null) {
                    onCreateProduct(name.trim(), cents)
                    showForm = false
                }
            },
            onDismiss = { showForm = false },
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre del producto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            MoneyField("Precio", priceText) { priceText = it }
        }
    }
}

@Composable
private fun CustomersScreen(
    customers: List<CustomerBalanceRow>,
    padding: PaddingValues,
    onCreateCustomer: (String, String?) -> Unit,
    onShowStatement: (CustomerBalanceRow) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showForm by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Clientes", style = MaterialTheme.typography.headlineSmall)
        FilledTonalButton(onClick = { showForm = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Agregar cliente") }
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
                Column(Modifier.padding(16.dp)) {
                    Text(row.customer.name)
                    Text(formatMoney(row.balanceCents))
                    row.customer.phone?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
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
                    showForm = false
                }
            },
            onDismiss = { showForm = false },
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
    tier: SubscriptionTier,
) {
    var filter by remember { mutableStateOf(DebtorFilter.OWING) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (state) {
            DashboardUiState.NoBusiness -> {
                Text("Bienvenido a Cuentivo", style = MaterialTheme.typography.headlineSmall)
                Text("Crea tu primer negocio para comenzar")
                FilledTonalButton(onClick = onCreateBusiness, modifier = Modifier.fillMaxWidth()) { Text("Crear mi negocio") }
            }
            is DashboardUiState.Content -> {
                Text(state.businessName, style = MaterialTheme.typography.headlineSmall)
                FilledTonalButton(onClick = onNewSale, modifier = Modifier.fillMaxWidth()) { Text("Nueva venta") }
                MetricCard("Por cobrar", state.receivableCents)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Vendido este mes", style = MaterialTheme.typography.labelLarge)
                            Text(formatMoney(state.soldCents), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Cobrado este mes", style = MaterialTheme.typography.labelLarge)
                            Text(formatMoney(state.collectedCents), style = MaterialTheme.typography.titleMedium)
                        }
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Quién me debe", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    AssistChip(onClick = onShareDebtorList, label = { Text("Compartir lista") })
                }
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

                Text("Tu cuenta", style = MaterialTheme.typography.titleMedium)
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
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
                // Google Play requires account deletion to be reachable from inside the app.
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Eliminar mi cuenta", color = MaterialTheme.colorScheme.error)
                }
            }
        }
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
