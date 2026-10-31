package com.cuentivo.app.ui.account

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing

const val PRIVACY_POLICY_URL = "https://cuentivo-politicasdeprivacidad.vercel.app/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    tier: SubscriptionTier,
    accountLabel: String?,
    onUpgrade: (SubscriptionTier) -> Unit,
    /** Pro's price as Play formats it, or null while it has not loaded. */
    proPrice: String? = null,
    /** AI's price as Play formats it, or null while it has not loaded. */
    aiPrice: String? = null,
    proTrialMonths: Int = 0,
    aiTrialMonths: Int = 0,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onDismiss: () -> Unit,
    /** Backup and restore, supplied by the host because it needs the database and the file picker. */
    backupSection: @androidx.compose.runtime.Composable () -> Unit = {},
    /** Whether the current session is an anonymous guest session. */
    isAnonymous: Boolean = false,
    /** Customer count used to contextualise retention value moments. */
    customerCount: Int = 0,
    /** Callback to open sign up / link account when guest wishes to preserve their ledger. */
    onSignUp: () -> Unit = {},
    /** Whether the daily overdue reminder is on; owned by the host's preferences. */
    reminderEnabled: Boolean = false,
    /** The chosen local hour (0..23); owned by the host's preferences. */
    reminderHour: Int = 9,
    onReminderEnabledChange: (Boolean) -> Unit = {},
    onReminderHourChange: (Int) -> Unit = {},
    /** Debug builds only: forces the free-plan footer on shares. Null hides the row entirely. */
    debugForceFreeShare: Boolean? = null,
    onDebugForceFreeShareChange: (Boolean) -> Unit = {},
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NeoSpacing.lg)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
                .testTag("account-sheet-scroll"),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.md),
        ) {
            Text(stringResource(R.string.account_title), style = MaterialTheme.typography.titleLarge)
            Text(
                accountLabel?.let { stringResource(R.string.account_signed_in_as, it) } ?: stringResource(R.string.account_signed_in),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isAnonymous) {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("account-guest-banner"),
                    shape = NeoShapes.card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NeoSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
                    ) {
                        Text(
                            text = stringResource(R.string.account_guest_card_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (customerCount > 0) {
                                stringResource(R.string.account_guest_card_detail_with_count, customerCount)
                            } else {
                                stringResource(R.string.account_guest_card_detail_generic)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        NeoAction(
                            label = stringResource(R.string.account_guest_card_action),
                            onClick = {
                                onDismiss()
                                onSignUp()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Above the plan and the sign-out: losing the phone is a likelier disaster than any
            // billing question, and this is the only thing that undoes it.
            backupSection()
            Text(
                stringResource(R.string.account_manage_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SubscriptionContent(
                tier = tier,
                onUpgrade = onUpgrade,
                proPrice = proPrice,
                aiPrice = aiPrice,
                proTrialMonths = proTrialMonths,
                aiTrialMonths = aiTrialMonths,
                isAnonymous = isAnonymous,
                customerCount = customerCount,
            )

            // Reminders sit above the account's exit points: turning them on or off is a decision
            // about the phone, and the sign-out and delete buttons are the doors this sheet exists
            // to guard. The whole section is purely local — no network, no customer contact.
            Text(
                stringResource(R.string.reminder_setting_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.reminder_setting_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.reminder_setting_time),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        stringResource(R.string.reminder_hour_option, reminderHour),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (reminderEnabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // One hour forward or back, wrapping at midnight. Disabled while reminders are off,
                // because choosing an hour for a reminder that will not run is setting nothing.
                val hourEarlierDescription = stringResource(R.string.reminder_hour_earlier)
                val hourLaterDescription = stringResource(R.string.reminder_hour_later)
                TextButton(
                    onClick = { onReminderHourChange((reminderHour - 1 + 24) % 24) },
                    enabled = reminderEnabled,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = hourEarlierDescription },
                ) {
                    Text("−")
                }
                TextButton(
                    onClick = { onReminderHourChange((reminderHour + 1) % 24) },
                    enabled = reminderEnabled,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = hourLaterDescription },
                ) {
                    Text("+")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val reminderSwitchDescription = stringResource(R.string.reminder_setting_title)
                Text(
                    stringResource(R.string.reminder_setting_enable),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = reminderEnabled,
                    onCheckedChange = onReminderEnabledChange,
                    modifier = Modifier
                        .testTag("reminder-enabled-switch")
                        .semantics { contentDescription = reminderSwitchDescription },
                )
            }
            if (debugForceFreeShare != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.debug_force_free_share),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = debugForceFreeShare,
                        onCheckedChange = onDebugForceFreeShareChange,
                        modifier = Modifier.testTag("debug-force-free-share-switch"),
                    )
                }
            }

            val context = LocalContext.current
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("privacy-policy-button"),
            ) {
                Text(stringResource(R.string.account_privacy_policy))
            }

            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_sign_out))
            }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.account_sign_out_title)) },
            text = {
                Text(stringResource(R.string.account_sign_out_detail))
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text(stringResource(R.string.account_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.form_cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.account_delete_title)) },
            text = {
                Text(stringResource(R.string.account_delete_detail))
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteAccount() }) {
                    Text(stringResource(R.string.account_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.form_cancel)) } },
        )
    }
}
