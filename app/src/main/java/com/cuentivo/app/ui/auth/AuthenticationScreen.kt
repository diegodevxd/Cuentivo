package com.cuentivo.app.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cuentivo.app.auth.CredentialsValidation
import com.cuentivo.app.auth.CredentialsValidator

/**
 * Sign-in and sign-up as two distinct views with a link between them, instead of two stacked
 * buttons on one form. Same public signature as before: the mode is purely internal state.
 *
 * The form sits on a translucent card over the theme's gradient, so the first screen of the app
 * already shows its own identity rather than a bare white column.
 */
@Composable
fun AuthenticationScreen(
    isWorking: Boolean,
    message: String?,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
) {
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf<CredentialsValidation?>(null) }
    var confirmMismatch by remember { mutableStateOf(false) }

    val submit: () -> Unit = {
        validation = CredentialsValidator.validate(email, password)
        confirmMismatch = isSignUp && password != confirmPassword
        if (validation == CredentialsValidation.Valid && !confirmMismatch) {
            if (isSignUp) onSignUp(email.trim(), password) else onSignIn(email.trim(), password)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Stand-in mark until the real icon exists: a mint disc with the initial.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "C",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Text(
                "Cuentivo",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Lleva las cuentas de tu negocio, aunque vendas fiado.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (isSignUp) "Crear cuenta" else "Iniciar sesión",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (isSignUp) {
                            "Crea tu cuenta para proteger y sincronizar la información de tu negocio."
                        } else {
                            "Inicia sesión para proteger y sincronizar la información de tu negocio."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; validation = null },
                        label = { Text("Correo electrónico") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; validation = null; confirmMismatch = false },
                        label = { Text("Contraseña") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (isSignUp) {
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it; confirmMismatch = false },
                            label = { Text("Confirma tu contraseña") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    when (validation) {
                        CredentialsValidation.InvalidEmail -> Text("Escribe un correo válido.", color = MaterialTheme.colorScheme.error)
                        CredentialsValidation.PasswordTooShort -> Text("La contraseña debe tener al menos 8 caracteres.", color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                    if (confirmMismatch) Text("Las contraseñas no coinciden.", color = MaterialTheme.colorScheme.error)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = submit,
                        enabled = !isWorking,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isWorking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(if (isSignUp) "Crear cuenta" else "Entrar")
                        }
                    }
                    TextButton(
                        onClick = {
                            isSignUp = !isSignUp
                            validation = null
                            confirmMismatch = false
                            confirmPassword = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isSignUp) "Ya tengo cuenta, iniciar sesión" else "¿No tienes cuenta? Crear una")
                    }
                }
            }
        }
    }
}
