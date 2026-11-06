package com.cuentivo.app.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * `ANIMATOR_DURATION_SCALE` es el ajuste de Opciones de desarrollador que Android usa para desactivar
 * animaciones del sistema; distinto de cero significa "se permite animar".
 *
 * Es exactamente la lectura que hacía esta pieza cuando vivía en `:app`, con el mismo valor por
 * defecto (1f) y el mismo significado.
 */
@Composable
actual fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}
