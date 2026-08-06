package com.cuentivo.app.ui.account

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.domain.SubscriptionTier
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
    /** Backup and restore, supplied by the host because it needs the database and the file picker. */
    backupSection: @androidx.compose.runtime.Composable () -> Unit = {},
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
            // Above the plan and the sign-out: losing the phone is a likelier disaster than any
            // billing question, and this is the only thing that undoes it.
            backupSection()
            Text(
                stringResource(R.string.account_manage_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SubscriptionContent(tier = tier, onUpgrade = onUpgrade)

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
