package com.cuentivo.app.ui.ai

import androidx.compose.runtime.Composable

/**
 * En iOS `IosVoiceRecognizer` pide el permiso solo y avisa con `PERMISSION_DENIED` si no lo dan.
 */
class IosVoicePermissionGateFactory : VoicePermissionGateFactory {

    @Composable
    override fun remember(onResult: (Boolean) -> Unit): VoicePermissionGate = object : VoicePermissionGate {
        override fun isGranted(): Boolean = true

        override fun request() = Unit
    }
}
