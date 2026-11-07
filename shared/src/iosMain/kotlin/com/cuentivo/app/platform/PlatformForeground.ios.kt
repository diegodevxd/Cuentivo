package com.cuentivo.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformLeaveForegroundEffect(key: Any?, onLeave: () -> Unit) {
    val currentOnLeave = rememberUpdatedState(onLeave)
    DisposableEffect(key) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            currentOnLeave.value()
        }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }
}
