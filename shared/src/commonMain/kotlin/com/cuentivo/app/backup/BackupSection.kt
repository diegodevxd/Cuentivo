package com.cuentivo.app.backup

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.cuentivo.app.platform.DocumentSaveTarget
import com.cuentivo.app.platform.PickedDocument
import com.cuentivo.app.platform.rememberDocumentOpener
import com.cuentivo.app.platform.rememberDocumentSaver
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoSpacing
// Los accesos de Res.string son propiedades de extensión generadas una por clave.
import com.cuentivo.shared.resources.*
import org.jetbrains.compose.resources.stringResource

/** Shortest password worth calling one. Below this the encryption is theatre. */
private const val MIN_PASSWORD = 8

/** Un `.cuentivo` no tiene tipo propio: se pide binario y el nombre lo pone [suggestedFileName]. */
private const val MIME_TYPE = "application/octet-stream"

/**
 * Export and restore, inside the account sheet.
 *
 * Free on every plan on purpose: this is not a feature, it is the difference between losing a
 * phone and losing a business's books.
 *
 * La selección de archivos no vive aquí: Android e iOS no eligen igual. Android pregunta dónde
 * guardar antes de cifrar, así que recibe un [DocumentSaveTarget] ya apuntado; iOS necesita el
 * archivo hecho para presentar su exportador, así que lo escribe primero. Por eso las dos firmas
 * reciben el destino y el documento, no una `Uri`.
 */
@Composable
fun BackupSection(
    onExport: (DocumentSaveTarget, String) -> Unit,
    onRestore: (PickedDocument, String) -> Unit,
    suggestedFileName: () -> String,
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
    var restoreDocument by remember { mutableStateOf<PickedDocument?>(null) }

    val saver = rememberDocumentSaver(MIME_TYPE) { target ->
        val password = exportPassword
        exportPassword = null
        if (password != null) onExport(target, password)
    }
    val opener = rememberDocumentOpener { document -> restoreDocument = document }

    val detailText = if (isAnonymous) {
        if (customerCount > 0) {
            stringResource(Res.string.backup_guest_detail_with_count, customerCount)
        } else {
            stringResource(Res.string.backup_guest_detail_generic)
        }
    } else if (hasCloudBackup) {
        stringResource(Res.string.backup_detail_cloud)
    } else {
        stringResource(Res.string.backup_detail)
    }

    Column(verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.backup_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = detailText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NeoAction(
            label = stringResource(Res.string.backup_export),
            onClick = { askExportPassword = true },
            modifier = Modifier.fillMaxWidth().testTag("backup-export"),
            enabled = !isWorking,
        )
        NeoAction(
            label = stringResource(Res.string.backup_restore),
            onClick = { opener.open() },
            modifier = Modifier.fillMaxWidth().testTag("backup-restore"),
            enabled = !isWorking,
            primary = false,
        )
    }

    if (askExportPassword) {
        PasswordDialog(
            title = stringResource(Res.string.backup_export_title),
            // Said before they choose it, not after they forget it: there is no way to recover a
            // backup whose password is lost, because nothing about it is stored anywhere.
            detail = stringResource(Res.string.backup_export_detail),
            confirmLabel = stringResource(Res.string.backup_export_confirm),
            requireRepeat = true,
            onAccept = { password ->
                askExportPassword = false
                exportPassword = password
                saver.create(suggestedFileName())
            },
            onDismiss = { askExportPassword = false },
        )
    }

    restoreDocument?.let { document ->
        PasswordDialog(
            title = stringResource(Res.string.backup_restore_title),
            detail = stringResource(Res.string.backup_restore_detail),
            confirmLabel = stringResource(Res.string.backup_restore_confirm),
            requireRepeat = false,
            onAccept = { password ->
                restoreDocument = null
                onRestore(document, password)
            },
            onDismiss = { restoreDocument = null },
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
                    label = { Text(stringResource(Res.string.backup_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = password.isNotEmpty() && !longEnough,
                    modifier = Modifier.fillMaxWidth().testTag("backup-password"),
                )
                if (password.isNotEmpty() && !longEnough) {
                    Text(
                        stringResource(Res.string.backup_password_too_short, MIN_PASSWORD),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (requireRepeat) {
                    OutlinedTextField(
                        value = repeated,
                        onValueChange = { repeated = it },
                        label = { Text(stringResource(Res.string.backup_password_repeat)) },
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
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.form_cancel)) }
        },
    )
}
