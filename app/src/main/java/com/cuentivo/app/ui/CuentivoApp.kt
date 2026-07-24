package com.cuentivo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cuentivo.app.ui.dashboard.DashboardUiState
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.ProductEntity
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

private enum class AppDestination(val label: String) { HOME("Inicio"), CUSTOMERS("Clientes"), NEW_SALE("Nueva venta"), MOVEMENTS("Movimientos"), MORE("Más") }

@Composable
fun CuentivoApp(state: DashboardUiState, customers: List<CustomerBalanceRow> = emptyList(), products: List<ProductEntity> = emptyList(), onCreateBusiness: () -> Unit = {}) {
    var destination by remember { mutableStateOf(AppDestination.HOME) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { item ->
                    NavigationBarItem(selected = destination == item, onClick = { destination = item }, icon = {}, label = { Text(item.label) })
                }
            }
        },
    ) { padding ->
        if (destination == AppDestination.HOME) DashboardScreen(state, padding, onCreateBusiness)
        else if (destination == AppDestination.CUSTOMERS) CustomersScreen(customers, padding)
        else if (destination == AppDestination.MORE) ProductsScreen(products, padding)
        else PlaceholderScreen(destination.label, padding)
    }
}

@Composable
private fun ProductsScreen(products: List<ProductEntity>, padding: PaddingValues) {
    var query by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Productos", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query, { query = it }, label = { Text("Buscar por nombre o SKU") }, modifier = Modifier.fillMaxWidth())
        val filtered = products.filter { it.name.contains(query, true) || (it.sku?.contains(query, true) == true) }
        if (filtered.isEmpty()) Text(if (query.isBlank()) "Aún no tienes productos registrados" else "No se encontraron productos")
        filtered.forEach { product -> Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(product.name); Text(formatMoney(product.salePriceCents)); product.category?.let { Text(it, style = MaterialTheme.typography.labelLarge) } } } }
    }
}

@Composable
private fun CustomersScreen(customers: List<CustomerBalanceRow>, padding: PaddingValues) {
    var query by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Clientes", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query, { query = it }, label = { Text("Buscar por nombre o teléfono") }, modifier = Modifier.fillMaxWidth())
        val filtered = customers.filter { it.customer.name.contains(query, true) || (it.customer.phone?.contains(query) == true) }
        if (filtered.isEmpty()) Text(if (query.isBlank()) "Aún no tienes clientes registrados" else "No se encontraron clientes")
        filtered.forEach { row -> Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(row.customer.name); Text(formatMoney(row.balanceCents)) } } }
    }
}

@Composable
private fun DashboardScreen(state: DashboardUiState, padding: PaddingValues, onCreateBusiness: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
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
                FilledTonalButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Nueva venta") }
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

@Composable
private fun PlaceholderScreen(label: String, padding: PaddingValues) {
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
        Text("$label próximamente", style = MaterialTheme.typography.headlineSmall)
        Text("Esta sección se construirá en el siguiente bloque de la Fase 2.")
    }
}

private fun formatMoney(cents: Long): String = NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    .format(BigDecimal.valueOf(cents, 2))
