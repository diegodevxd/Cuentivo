package com.cuentivo.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cuentivo.app.ai.AndroidVoiceRecognizer
import com.cuentivo.app.data.local.ProductImageStore
import com.cuentivo.app.settings.AndroidLanguageControl
import com.cuentivo.app.ui.ai.AndroidLedgerCapture
import com.cuentivo.app.ui.ai.AndroidVoicePermissionGateFactory
import com.cuentivo.app.ui.ai.debugAiTransport
import com.cuentivo.app.ui.product.AndroidImagePickerFactory

@Composable
fun rememberAndroidCuentivoAppPlatform(): CuentivoAppPlatform {
    val context = LocalContext.current
    return remember(context) {
        CuentivoAppPlatform(
            photoStore = ProductImageStore(context),
            imagePickers = AndroidImagePickerFactory,
            language = AndroidLanguageControl(context),
            openUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            aiLabTransport = debugAiTransport(),
            rememberAiLabServices = {
                val current = LocalContext.current
                remember(current) {
                    val ledgerCapture = AndroidLedgerCapture(current)
                    AiLabServices(
                        ledgerPagePickerFactory = ledgerCapture,
                        ledgerPageFiles = ledgerCapture,
                        voiceRecognizerFactory = { AndroidVoiceRecognizer(current) },
                        voicePermissionGateFactory = AndroidVoicePermissionGateFactory(current),
                    )
                }
            },
        )
    }
}
