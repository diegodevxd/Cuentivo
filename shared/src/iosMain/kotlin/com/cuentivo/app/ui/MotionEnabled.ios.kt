package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/**
 * El equivalente de iOS es "Reducir movimiento" (Ajustes › Accesibilidad › Movimiento), expuesto por
 * `UIAccessibilityIsReduceMotionEnabled()`.
 *
 * Se invierte porque la función de UIKit pregunta si hay que *reducirlo*, y este valor dice si se
 * permite animar — el mismo significado que el ajuste de Android.
 */
@Composable
actual fun rememberMotionEnabled(): Boolean = remember { !UIAccessibilityIsReduceMotionEnabled() }
