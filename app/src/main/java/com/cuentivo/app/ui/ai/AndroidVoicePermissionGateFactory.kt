package com.cuentivo.app.ui.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat

class AndroidVoicePermissionGateFactory(private val context: Context) : VoicePermissionGateFactory {

    @Composable
    override fun remember(onResult: (Boolean) -> Unit): VoicePermissionGate {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> onResult(granted) }
        return remember(launcher) {
            object : VoicePermissionGate {
                override fun isGranted(): Boolean = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED

                override fun request() {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}
