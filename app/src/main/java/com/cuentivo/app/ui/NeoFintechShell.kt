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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
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
    /**
     * A dedicated task that owns the whole viewport — customer detail, for instance — brings its
     * own back arrow and title. Leaving the brand row above it would stack two headers and steal
     * 52 dp the reference does not spend. The bottom bar deliberately stays.
     */
    showBrandHeader: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            if (!showBrandHeader) return@Scaffold
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The app draws edge-to-edge, so without this the logo and the bell sit under
                    // the status bar's clock and icons instead of below them.
                    .statusBarsPadding()
                    .height(52.dp)
                    .padding(horizontal = NeoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .height(48.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Cuenta"
                            role = Role.Button
                        }
                        .clickable(onClick = onOpenAccount)
                        .padding(end = NeoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = NeoShapes.row,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("C", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(
                        text = "Cuentivo",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = NeoSpacing.xs),
                    )
                }
                Box(Modifier.weight(1f))
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
        val isSelected = selectedDestination == destination
        val markerColor by animateColorAsState(
            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            animationSpec = tween(NeoMotion.STANDARD_MS),
            label = "navigation marker color",
        )
        val markerHeight by animateDpAsState(
            targetValue = if (isSelected) 3.dp else 0.dp,
            animationSpec = tween(NeoMotion.STANDARD_MS),
            label = "navigation marker height",
        )
        val contentColor by animateColorAsState(
            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = tween(NeoMotion.STANDARD_MS),
            label = "navigation content color",
        )
        Column(
            modifier = modifier
                .testTag("main-destination-${destination.name.lowercase()}")
                .semantics {
                    selected = isSelected
                    role = Role.Tab
                }
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
