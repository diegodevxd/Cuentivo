package com.cuentivo.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.cuentivo.app.domain.Money
import platform.UIKit.UIViewController

/**
 * Punto de entrada de la app de iOS: un `ComposeUIViewController` con el dominio de `:shared`.
 *
 * Es la prueba de que la ruta sin Mac funciona: si esto arranca en el simulador, el mismo
 * `domain/` que usa Android compila y corre en iOS. La UI completa llega en la Fase 5.
 */
fun MainViewController(): UIViewController = ComposeUIViewController { CuentivoIosScreen() }

@Composable
private fun CuentivoIosScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "Cuentivo", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = Money.format(12_345),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
