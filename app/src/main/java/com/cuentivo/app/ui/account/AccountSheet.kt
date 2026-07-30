package com.cuentivo.app.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cuentivo.app.domain.FeatureAccessManager
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    tier: SubscriptionTier,
    accountLabel: String?,
    onUpgrade: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NeoSpacing.lg)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.md),
        ) {
            Text("Cuenta", style = MaterialTheme.typography.titleLarge)
            Text(
                accountLabel?.let { "Sesión iniciada como $it" } ?: "Sesión iniciada.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Administra el plan y la sesión de esta cuenta.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AccountPlanSummary(tier = tier, onUpgrade = onUpgrade)

            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Cerrar sesión")
            }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Eliminar mi cuenta", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("¿Cerrar sesión?") },
            text = {
                Text(
                    "Tu información seguirá aquí si vuelves a entrar con esta misma cuenta. Pero si " +
                        "alguien más inicia sesión en este teléfono, lo que todavía no se haya " +
                        "sincronizado se borrará."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Cerrar sesión") }
            },
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

@Composable
private fun AccountPlanSummary(tier: SubscriptionTier, onUpgrade: () -> Unit) {
    val free = tier == SubscriptionTier.FREE
    Surface(
        shape = NeoShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(NeoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
        ) {
            Text(if (free) "Plan Gratis" else "Cuentivo Pro", style = MaterialTheme.typography.titleMedium)
            Text(
                if (free) {
                    "Hasta ${FeatureAccessManager.FREE_CUSTOMER_LIMIT} clientes y " +
                        "${FeatureAccessManager.FREE_SALE_LIMIT} ventas registradas."
                } else {
                    "Clientes y ventas ilimitados, respaldo y sincronización en la nube."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (free) {
                PlanDetail(
                    "Incluye ventas, abonos, estados de cuenta y compartir la cuenta de un cliente. " +
                        "No incluye respaldo en la nube, fotos de productos o comprobantes, " +
                        "importar/exportar Excel, ni equipo con roles.",
                )
                NeoAction("Suscribirme a Pro", onUpgrade, Modifier.fillMaxWidth())
                Text(
                    "El cobro se hace por Google Play y puedes cancelarlo cuando quieras desde la tienda. " +
                        "Tus datos no se borran si cancelas.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                PlanDetail(
                    "Incluye clientes y ventas ilimitados, fotos de productos y comprobantes, " +
                        "importar y exportar productos en Excel, compartir la lista completa de deudores, " +
                        "hasta 5 integrantes con roles e historial y auditoría completos.",
                )
                Text(
                    "Tu suscripción se administra desde Google Play.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlanDetail(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
