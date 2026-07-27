package com.cuentivo.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cuentivo.app.domain.Alert

/**
 * What needs attention today, worst trouble first.
 *
 * A bottom sheet rather than a sixth tab: five is already the comfortable maximum for the bottom
 * bar, and this is something you check, not somewhere you live.
 *
 * Every alert about a person is tappable and lands on their statement — a notification that tells
 * you something is wrong without taking you where you can fix it only makes you feel bad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsSheet(
    alerts: List<Alert>,
    onOpenCustomer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Avisos", style = MaterialTheme.typography.headlineSmall)

            if (alerts.isEmpty()) {
                // Worth saying out loud: "nothing is late" is genuinely good news in this app.
                Text("Nada pendiente por ahora. Nadie se te ha atrasado.")
                return@Column
            }

            alerts.forEach { alert ->
                val accent = when (alert.severity) {
                    Alert.Severity.OVERDUE -> MaterialTheme.colorScheme.error
                    Alert.Severity.DUE_SOON -> MaterialTheme.colorScheme.tertiary
                    Alert.Severity.INFO -> MaterialTheme.colorScheme.secondary
                }
                val open = alert.customerId?.let { id -> { onDismiss(); onOpenCustomer(id) } }
                val shape = RoundedCornerShape(18.dp)
                val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                val body: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Severity is carried by position and wording too, not colour alone: a tint
                        // is the first thing to vanish on a cheap screen in the sun.
                        Box(Modifier.width(5.dp).height(58.dp).background(accent))
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(alert.title, style = MaterialTheme.typography.titleMedium)
                            Text(alert.detail, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (open == null) {
                    Card(shape = shape, border = border, modifier = Modifier.fillMaxWidth()) { body() }
                } else {
                    Card(onClick = open, shape = shape, border = border, modifier = Modifier.fillMaxWidth()) { body() }
                }
            }
        }
    }
}
