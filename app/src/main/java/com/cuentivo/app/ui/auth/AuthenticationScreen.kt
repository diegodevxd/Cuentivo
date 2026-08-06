package com.cuentivo.app.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.auth.CredentialsValidation
import com.cuentivo.app.auth.CredentialsValidator
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoPanel
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing

/** The launcher icon's own backdrop, so the mark here is the one on the home screen. */
private val BrandInk = Color(0xFF001D26)

/**
 * Sign-in and sign-up as one compact panel with a link between the two modes.
 *
 * The public signature is unchanged: the mode is internal state, and both callbacks reach Firebase
 * exactly as before. `CredentialsValidator` remains the only judge of what counts as valid — this
 * screen asks it on every keystroke so the action can refuse to light up, rather than letting
 * someone press a button that was always going to fail.
 *
 * The mark is the real launcher foreground rather than a lettered stand-in, drawn over the same ink
 * the adaptive icon uses. Whoever installed the app recognises it.
 */
@Composable
fun AuthenticationScreen(
    isWorking: Boolean,
    message: String?,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onResetPassword: (String) -> Unit = {},
    /** Clears whatever the host was showing. A failure from one mode is not news in the other. */
    onModeChange: () -> Unit = {},
) {
    var isSignUp by remember { mutableStateOf(false) }
    var resetRequested by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    // Derived every pass, never stored: the validator is the single source of truth for validity.
    val validation = CredentialsValidator.validate(email, password)
    val passwordsMatch = !isSignUp || (confirmPassword.isNotEmpty() && confirmPassword == password)
    val canSubmit = !isWorking && validation == CredentialsValidation.Valid && passwordsMatch

    // safeDrawingPadding first: the app draws edge-to-edge, and without it this screen — which
    // renders outside the shell's Scaffold — drew its brand mark and fields under the status bar.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
        val horizontalGutter = maxOf(16, layout.horizontalPaddingDp)
        Column(
            // fillMaxSize before the scroll fixes the column's minimum height to the viewport, so
            // Arrangement.Center centres the form when it fits and still scrolls once the keyboard
            // shrinks the space below it — a fixed Center without the scroll would just clip.
            modifier = Modifier
                .widthIn(max = layout.contentMaxWidthDp.dp)
                .fillMaxSize()
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalGutter.dp)
                .padding(vertical = NeoSpacing.xl),
            verticalArrangement = Arrangement.Center,
        ) {
            BrandHeader(isSignUp = isSignUp)

            NeoPanel(modifier = Modifier.fillMaxWidth().testTag("authentication-panel")) {
                Column(verticalArrangement = Arrangement.spacedBy(NeoSpacing.md)) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.auth_email_label)) },
                singleLine = true,
                isError = email.isNotBlank() && validation == CredentialsValidation.InvalidEmail,
                shape = NeoShapes.row,
                modifier = Modifier.fillMaxWidth(),
            )
            // Beside the field it belongs to, and only once there is something to judge: an error
            // shown before the first character is typed is just noise.
            if (email.isNotBlank() && validation == CredentialsValidation.InvalidEmail) {
                FieldError(stringResource(R.string.auth_invalid_email))
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.auth_password_label)) },
                singleLine = true,
                isError = password.isNotBlank() && validation == CredentialsValidation.PasswordTooShort,
                visualTransformation = PasswordVisualTransformation(),
                shape = NeoShapes.row,
                modifier = Modifier.fillMaxWidth(),
            )
            if (password.isNotBlank() && validation == CredentialsValidation.PasswordTooShort) {
                FieldError(stringResource(R.string.auth_password_too_short))
            }

            if (isSignUp) {
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text(stringResource(R.string.auth_confirm_password_label)) },
                    singleLine = true,
                    isError = confirmPassword.isNotEmpty() && confirmPassword != password,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = NeoShapes.row,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (confirmPassword.isNotEmpty() && confirmPassword != password) {
                    FieldError(stringResource(R.string.auth_password_mismatch))
                }
            }

            // Whatever Firebase said, in the one place the person is already looking.
            message?.let { FieldError(it) }

            NeoAction(
                label = stringResource(R.string.auth_continue),
                onClick = {
                    if (canSubmit) {
                        if (isSignUp) onSignUp(email.trim(), password) else onSignIn(email.trim(), password)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit,
            )
            if (isWorking) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }

            TextButton(
                onClick = {
                    isSignUp = !isSignUp
                    confirmPassword = ""
                    resetRequested = false
                    // Without this the "could not create the account" error followed people over to
                    // the sign-in form and read as if signing in had failed too.
                    onModeChange()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(stringResource(if (isSignUp) R.string.auth_switch_to_sign_in else R.string.auth_switch_to_sign_up))
            }
            // Only on the sign-in side: offering it while someone is choosing a password reads as
            // an error message about the password they are still typing.
            if (!isSignUp) {
                TextButton(
                    onClick = {
                        onResetPassword(email.trim())
                        resetRequested = true
                    },
                    enabled = !isWorking && validation != CredentialsValidation.InvalidEmail && email.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("auth-forgot-password"),
                ) {
                    Text(stringResource(R.string.auth_forgot_password))
                }
                // Says the same thing whether or not that address has an account. Confirming which
                // addresses are registered hands an attacker the customer list.
                if (resetRequested) {
                    Text(
                        text = stringResource(R.string.auth_reset_sent),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
                }
            }
        }
    }
}

@Composable
private fun BrandHeader(isSignUp: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = NeoShapes.card,
            color = BrandInk,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
            modifier = Modifier.size(56.dp),
        ) {
            // The adaptive foreground carries the icon's safe-zone padding, so it is scaled up to
            // fill this tile the way the launcher's own mask crops it.
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = "Cuentivo",
                modifier = Modifier.fillMaxSize().scale(1.5f),
            )
        }
        Spacer(Modifier.height(NeoSpacing.md))
        Text(
            text = stringResource(if (isSignUp) R.string.auth_sign_up_title else R.string.auth_sign_in_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(NeoSpacing.xs))
        Text(
            text = stringResource(R.string.auth_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FieldError(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
