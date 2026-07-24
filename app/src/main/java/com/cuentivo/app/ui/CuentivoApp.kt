package com.cuentivo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import com.cuentivo.app.ui.dashboard.DashboardUiState
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

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
    onCreateBusiness: () -> Unit = {},
    onCreateCustomer: (String) -> Unit = {},
    onCreateProduct: (String, Long) -> Unit = { _, _ -> },
    onCreateSale: (CustomerBalanceRow, ProductEntity) -> Unit = { _, _ -> },
    onCreatePayment: (CustomerBalanceRow, Long) -> Unit = { _, _ -> },
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    var destination by remember { mutableStateOf(AppDestination.HOME) }
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
            AppDestination.HOME -> DashboardScreen(state, padding, onCreateBusiness, onNewSale = { destination = AppDestination.NEW_SALE })
            AppDestination.CUSTOMERS -> CustomersScreen(customers, padding, onCreateCustomer)
            AppDestination.PRODUCTS -> ProductsScreen(products, padding, onCreateProduct)
            AppDestination.NEW_SALE -> NewSaleScreen(customers, products, padding, onCreateSale)
            AppDestination.PAYMENTS -> PaymentScreen(customers, padding, onCreatePayment)
        }
    }
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
 * BigDecimal.longValueExact() refuses more than two decimals instead of truncating,
 * so fractional money can never slip into the integer-cents system. No Double anywhere.
 */
internal fun pesosToCents(text: String): Long? = try {
    BigDecimal(text.trim()).movePointRight(2).longValueExact().takeIf { it > 0 }
} catch (error: Exception) {
    null
}

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

@Composable
private fun NewSaleScreen(
    customers: List<CustomerBalanceRow>,
    products: List<ProductEntity>,
    padding: PaddingValues,
    onCreateSale: (CustomerBalanceRow, ProductEntity) -> Unit,
) {
    var customer by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    var product by remember { mutableStateOf<ProductEntity?>(null) }
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
            selected = product,
            placeholder = "Toca para elegir el producto",
            options = products,
            optionTitle = { it.name },
            optionDetail = { formatMoney(it.salePriceCents) },
            emptyText = "Primero agrega un producto en la pestaña Productos",
            onSelect = { product = it },
        )
        val chosenCustomer = customer
        val chosenProduct = product
        if (chosenCustomer != null && chosenProduct != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Resumen", style = MaterialTheme.typography.labelLarge)
                    Text("${chosenProduct.name} para ${chosenCustomer.customer.name}")
                    Text(formatMoney(chosenProduct.salePriceCents), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        FilledTonalButton(
            enabled = chosenCustomer != null && chosenProduct != null,
            onClick = {
                if (chosenCustomer != null && chosenProduct != null) {
                    onCreateSale(chosenCustomer, chosenProduct)
                    customer = null
                    product = null
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
        customer?.let { Text("Saldo actual: ${formatMoney(it.balanceCents)}", style = MaterialTheme.typography.titleMedium) }
        MoneyField("Monto del abono", amountText) { amountText = it }
        val cents = pesosToCents(amountText)
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
            Card(modifier = Modifier.fillMaxWidth()) {
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
private fun CustomersScreen(customers: List<CustomerBalanceRow>, padding: PaddingValues, onCreateCustomer: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var showForm by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CustomerBalanceRow?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Clientes", style = MaterialTheme.typography.headlineSmall)
        FilledTonalButton(onClick = { showForm = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Agregar cliente") }
        OutlinedTextField(query, { query = it }, label = { Text("Buscar por nombre o teléfono") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val filtered = customers.filter { it.customer.name.contains(query, true) || (it.customer.phone?.contains(query) == true) }
        if (filtered.isEmpty()) Text(if (query.isBlank()) "Aún no tienes clientes registrados" else "No se encontraron clientes")
        selected?.let { Text("Saldo de ${it.customer.name}: ${formatMoney(it.balanceCents)}", style = MaterialTheme.typography.titleMedium) }
        filtered.forEach { row ->
            Card(onClick = { selected = row }, modifier = Modifier.fillMaxWidth()) {
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
        FormDialog(
            title = "Nuevo cliente",
            confirmEnabled = name.isNotBlank(),
            onConfirm = {
                if (name.isNotBlank()) {
                    onCreateCustomer(name.trim())
                    showForm = false
                }
            },
            onDismiss = { showForm = false },
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre del cliente") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DashboardScreen(state: DashboardUiState, padding: PaddingValues, onCreateBusiness: () -> Unit, onNewSale: () -> Unit) {
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
                Text("Buenos días, ${state.businessName}", style = MaterialTheme.typography.headlineSmall)
                FilledTonalButton(onClick = onNewSale, modifier = Modifier.fillMaxWidth()) { Text("Nueva venta") }
                MetricCard("Por cobrar", state.receivableCents)
                MetricCard("Vendido este mes", state.soldCents)
                MetricCard("Cobrado este mes", state.collectedCents)
                if (state.overdueCents > 0) MetricCard("Pagos vencidos", state.overdueCents)
                Text("${state.customersWithDebt} clientes por cobrar")
            }
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

private fun formatMoney(cents: Long): String = NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    .format(BigDecimal.valueOf(cents, 2))
