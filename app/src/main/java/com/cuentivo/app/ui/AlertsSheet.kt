package com.cuentivo.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.domain.Alert

/** Two lines plus a severity word, matching the density of the other compact rows. */
private val ALERT_ROW_HEIGHT = 64.dp

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
    /** Sends a short reminder for that customer. Absent for alerts that belong to nobody. */
    onRemindCustomer: (String) -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AlertsContent(
            alerts = alerts,
            // Routing away closes the sheet first, so you land on the customer rather than on a
            // sheet still covering them.
            onRemindAlert = onRemindCustomer,
            onOpenAlert = { customerId -> onDismiss(); onOpenCustomer(customerId) },
        )
    }
}

/**
 * The alert list itself, free of the window it happens to be shown in.
 *
 * Extracted because a `ModalBottomSheet` lives in its own window: a capture of the app's
 * composition would come out showing the screen underneath with no sheet on it at all. Rendering
 * this directly lets a reference image show the real rows, while [AlertsSheet] stays the thing
 * production uses and the behaviour suites drive.
 */
@Composable
internal fun AlertsContent(
    alerts: List<Alert>,
    onOpenAlert: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemindAlert: (String) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NeoSpacing.lg)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
    ) {
        Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.titleLarge)

        if (alerts.isEmpty()) {
            // Worth saying out loud: "nothing is late" is genuinely good news in this app, and
            // there is no action to offer for it.
            NeoEmptyState(
                title = stringResource(R.string.alerts_empty_title),
                detail = stringResource(R.string.alerts_empty_detail),
            )
            return@Column
        }

        alerts.forEach { alert -> AlertRow(alert = alert, onOpenAlert = onOpenAlert, onRemindAlert = onRemindAlert) }
    }
}

@Composable
private fun AlertRow(alert: Alert, onOpenAlert: (String) -> Unit, onRemindAlert: (String) -> Unit) {
    val tone = alert.severity.tone()
    val open = alert.customerId?.let { id -> { onOpenAlert(id) } }
    val rowModifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = ALERT_ROW_HEIGHT)
        .let { base -> if (open == null) base else base.clickable(onClick = open) }

    Card(
        modifier = rowModifier,
        shape = NeoShapes.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Severity is carried by the word as much as by the bar: a tint is the first thing to
            // vanish on a cheap screen in the sun, and the one thing a screen reader never gets.
            Box(Modifier.width(4.dp).height(ALERT_ROW_HEIGHT).background(tone.accent))
            Column(
                modifier = Modifier
                    .padding(horizontal = NeoSpacing.md, vertical = NeoSpacing.sm)
                    // weight, not fillMaxWidth: the text used to take the whole row and push the
                    // Cobrar button off the edge of the screen.
                    .weight(1f),
            ) {
                Text(
                    text = tone.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tone.accent,
                    maxLines = 1,
                )
                Text(
                    text = alert.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = alert.detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Collecting is the actual daily job, and doing it meant leaving this list, finding the
            // customer and opening their statement. One tap instead.
            alert.customerId?.let { customerId ->
                val remindLabel = stringResource(R.string.alerts_remind)
                TextButton(
                    onClick = { onRemindAlert(customerId) },
                    modifier = Modifier
                        .padding(end = NeoSpacing.sm)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("alert-remind"),
                ) { Text(remindLabel) }
            }
        }
    }
}

private data class AlertTone(val label: String, val accent: Color)

/**
 * Red for late, cyan for debt that wants attention, neutral for everything else.
 *
 * The words are uppercase because they are eyebrows, the same as every other label of this kind in
 * the app — and because "Vencido" in sentence case is already the dashboard's overdue metric.
 *
 * Green is deliberately absent here: it is reserved for money actually collected, and a plan-limit
 * notice is not good news. Painting it green would spend the one colour that means "paid".
 */
@Composable
private fun Alert.Severity.tone(): AlertTone = when (this) {
    Alert.Severity.OVERDUE -> AlertTone(stringResource(R.string.alerts_severity_overdue), MaterialTheme.colorScheme.error)
    Alert.Severity.DUE_SOON -> AlertTone(stringResource(R.string.alerts_severity_due_soon), MaterialTheme.colorScheme.primary)
    Alert.Severity.INFO -> AlertTone(stringResource(R.string.alerts_severity_info), MaterialTheme.colorScheme.onSurfaceVariant)
}
