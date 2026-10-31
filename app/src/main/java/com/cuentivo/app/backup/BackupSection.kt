package com.cuentivo.app.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.cuentivo.app.R
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoSpacing
import java.time.LocalDate

/** Shortest password worth calling one. Below this the encryption is theatre. */
private const val MIN_PASSWORD = 8

/**
 * Export and restore, inside the account sheet.
 *
 * Free on every plan on purpose: this is not a feature, it is the difference between losing a
 * phone and losing a business's books.
 */
@Composable
fun BackupSection(
    onExport: (Uri, String) -> Unit,
    onRestore: (Uri, String) -> Unit,
    isWorking: Boolean = false,
    /**
     * Whether this account's data is already being kept in the cloud.
     *
     * Only the explanatory line changes; the buttons stay identical on every plan. Telling a Pro
     * subscriber "your data lives only on this phone" — directly above the plan card that sells
     * them cloud sync — reads as either a broken promise or a lie, and it is neither. Defaults to
     * false so the cautious sentence is what shows whenever the plan is not known yet.
     */
    hasCloudBackup: Boolean = false,
    /** Whether the current session is an anonymous guest session. */
    isAnonymous: Boolean = false,
    /** Customer count used to make the backup retention message concrete. */
    customerCount: Int = 0,
) {
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var askExportPassword by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val password = exportPassword
        exportPassword = null
        if (uri != null && password != null) onExport(uri, password)
    }
    // Wide open rather than a custom MIME: file managers and cloud drives label an unknown
    // extension inconsistently, and a strict filter greys out the very file they came to pick.
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri
    }

    val detailText = if (isAnonymous) {
        if (customerCount > 0) {
            stringResource(R.string.backup_guest_detail_with_count, customerCount)
        } else {
            stringResource(R.string.backup_guest_detail_generic)
        }
    } else if (hasCloudBackup) {
        stringResource(R.string.backup_detail_cloud)
    } else {
        stringResource(R.string.backup_detail)
    }

    Column(verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = detailText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NeoAction(
            label = stringResource(R.string.backup_export),
            onClick = { askExportPassword = true },
            modifier = Modifier.fillMaxWidth().testTag("backup-export"),
            enabled = !isWorking,
        )
        NeoAction(
            label = stringResource(R.string.backup_restore),
            onClick = { openLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().testTag("backup-restore"),
            enabled = !isWorking,
            primary = false,
        )
    }

    if (askExportPassword) {
        PasswordDialog(
            title = stringResource(R.string.backup_export_title),
            // Said before they choose it, not after they forget it: there is no way to recover a
            // backup whose password is lost, because nothing about it is stored anywhere.
            detail = stringResource(R.string.backup_export_detail),
            confirmLabel = stringResource(R.string.backup_export_confirm),
            requireRepeat = true,
            onAccept = { password ->
                askExportPassword = false
                exportPassword = password
                createLauncher.launch(DatabaseBackup.suggestedFileName(LocalDate.now().toString()))
            },
            onDismiss = { askExportPassword = false },
        )
    }

    restoreUri?.let { uri ->
        PasswordDialog(
            title = stringResource(R.string.backup_restore_title),
            detail = stringResource(R.string.backup_restore_detail),
            confirmLabel = stringResource(R.string.backup_restore_confirm),
            requireRepeat = false,
            onAccept = { password ->
                restoreUri = null
                onRestore(uri, password)
            },
            onDismiss = { restoreUri = null },
        )
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    detail: String,
    confirmLabel: String,
    requireRepeat: Boolean,
    onAccept: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val longEnough = password.length >= MIN_PASSWORD
    val matches = !requireRepeat || repeated == password
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm)) {
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = password.isNotEmpty() && !longEnough,
                    modifier = Modifier.fillMaxWidth().testTag("backup-password"),
                )
                if (password.isNotEmpty() && !longEnough) {
                    Text(
                        stringResource(R.string.backup_password_too_short, MIN_PASSWORD),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (requireRepeat) {
                    OutlinedTextField(
                        value = repeated,
                        onValueChange = { repeated = it },
                        label = { Text(stringResource(R.string.backup_password_repeat)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = repeated.isNotEmpty() && repeated != password,
                        modifier = Modifier.fillMaxWidth().testTag("backup-password-repeat"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (longEnough && matches) onAccept(password) },
                enabled = longEnough && matches,
                modifier = Modifier.testTag("backup-password-accept"),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.form_cancel)) }
        },
    )
}
