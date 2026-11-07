package com.cuentivo.app.platform

import androidx.compose.runtime.Composable

/** Corre [onLeave] cuando la app deja el primer plano. Un diálogo de permiso NO cuenta en iOS. */
@Composable
expect fun PlatformLeaveForegroundEffect(key: Any?, onLeave: () -> Unit)
