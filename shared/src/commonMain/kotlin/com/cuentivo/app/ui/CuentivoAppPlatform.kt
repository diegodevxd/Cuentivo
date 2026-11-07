package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import com.cuentivo.app.ai.VoiceRecognizer
import com.cuentivo.app.platform.ImagePickerFactory
import com.cuentivo.app.platform.LanguageControl
import com.cuentivo.app.platform.ProductPhotoStore
import com.cuentivo.app.ui.ai.AiDebugTransport
import com.cuentivo.app.ui.ai.LedgerPageFiles
import com.cuentivo.app.ui.ai.LedgerPagePickerFactory
import com.cuentivo.app.ui.ai.VoicePermissionGateFactory

class CuentivoAppPlatform(
    val photoStore: ProductPhotoStore,
    val imagePickers: ImagePickerFactory,
    val language: LanguageControl,
    val openUrl: (String) -> Unit,
    val aiLabTransport: AiDebugTransport,
    /** Se llama sólo dentro de la rama que abre el laboratorio: ahí se crean y recuerdan. */
    val rememberAiLabServices: @Composable () -> AiLabServices,
)

class AiLabServices(
    val ledgerPagePickerFactory: LedgerPagePickerFactory,
    val ledgerPageFiles: LedgerPageFiles,
    val voiceRecognizerFactory: () -> VoiceRecognizer,
    val voicePermissionGateFactory: VoicePermissionGateFactory,
)
