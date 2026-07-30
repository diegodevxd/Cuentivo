package com.cuentivo.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The four places in the app. Nueva venta deliberately remains an action, not a destination. */
enum class MainDestination {
    HOME,
    CUSTOMERS,
    PRODUCTS,
    PAYMENTS,
}

/** Small pure policy surface so action semantics do not get entangled with Compose state. */
object MainNavigationPolicy {
    val destinations = listOf(
        MainDestination.HOME,
        MainDestination.CUSTOMERS,
        MainDestination.PRODUCTS,
        MainDestination.PAYMENTS,
    )

    fun destinationAfterNewSale(current: MainDestination): MainDestination = current
}

@Composable
fun NeoFintechShell(
    destination: MainDestination,
    onDestination: (MainDestination) -> Unit,
    onNewSale: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenAlerts: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = NeoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenAccount, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = "Cuenta",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "Cuentivo",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenAlerts, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = "Avisos",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        snackbarHost = snackbarHost,
        bottomBar = {
            CompactBottomNavigation(
                destination = destination,
                onDestination = onDestination,
                onNewSale = onNewSale,
            )
        },
        content = content,
    )
}

@Composable
private fun CompactBottomNavigation(
    destination: MainDestination,
    onDestination: (MainDestination) -> Unit,
    onNewSale: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = NeoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ShellDestination.HOME.item(destination, onDestination, Modifier.weight(1f))
            ShellDestination.CUSTOMERS.item(destination, onDestination, Modifier.weight(1f))
            NewSaleNavigationAction(onClick = onNewSale, modifier = Modifier.weight(1f))
            ShellDestination.PRODUCTS.item(destination, onDestination, Modifier.weight(1f))
            ShellDestination.PAYMENTS.item(destination, onDestination, Modifier.weight(1f))
        }
    }
}

private enum class ShellDestination(
    val destination: MainDestination,
    val label: String,
    val icon: ImageVector,
) {
    HOME(MainDestination.HOME, "Inicio", Icons.Filled.Home),
    CUSTOMERS(MainDestination.CUSTOMERS, "Clientes", Icons.Filled.Person),
    PRODUCTS(MainDestination.PRODUCTS, "Productos", Icons.Filled.Star),
    PAYMENTS(MainDestination.PAYMENTS, "Abonos", Icons.Filled.CheckCircle),
    ;

    @Composable
    fun item(
        selectedDestination: MainDestination,
        onDestination: (MainDestination) -> Unit,
        modifier: Modifier,
    ) {
        val selected = selectedDestination == destination
        val markerColor by animateColorAsState(
            targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            animationSpec = tween(NeoMotion.STANDARD_MS),
            label = "navigation marker color",
        )
        val markerHeight by animateDpAsState(
            targetValue = if (selected) 3.dp else 0.dp,
            animationSpec = tween(NeoMotion.STANDARD_MS),
            label = "navigation marker height",
        )
        val contentColor by animateColorAsState(
            targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = tween(NeoMotion.STANDARD_MS),
            label = "navigation content color",
        )
        Column(
            modifier = modifier
                .height(56.dp)
                .clickable { onDestination(destination) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .height(markerHeight)
                    .width(20.dp)
                    .background(markerColor),
            )
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(19.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor, maxLines = 1)
        }
    }
}

@Composable
private fun NewSaleNavigationAction(onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .height(56.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = NeoShapes.row,
            modifier = Modifier.size(30.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = "Nueva venta", modifier = Modifier.size(20.dp))
            }
        }
        Text("Nueva venta", style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
