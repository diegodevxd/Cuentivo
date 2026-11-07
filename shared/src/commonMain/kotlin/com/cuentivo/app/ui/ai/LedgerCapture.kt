package com.cuentivo.app.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.cuentivo.app.ai.ledger.LedgerRegion

/** [path] es un identificador opaco para [LedgerPageFiles]: la pantalla no lo interpreta. */
data class PreparedLedgerPage(val path: String, val mimeType: String)

interface LedgerPagePicker {
    fun pickFromGallery()
    /** Falso si la cámara no pudo abrirse; entonces no llegará ninguna respuesta. */
    fun takePhoto(): Boolean
}

fun interface LedgerPagePickerFactory {
    @Composable
    fun remember(
        onPrepared: (PreparedLedgerPage) -> Unit,
        onCancelled: () -> Unit,
        onFailed: () -> Unit,
    ): LedgerPagePicker
}

interface LedgerPageFiles {
    fun readBytes(path: String): ByteArray?
    fun delete(path: String)
    /** Equivale a `captureStore.cleanupAll()` más `cameraStore.deleteStale(except = null)`. */
    fun cleanupAll()
    /** Equivale solo a `cameraStore.deleteStale(except = null)`. */
    fun deleteStaleCaptures()
    fun decodeCrop(path: String, region: LedgerRegion): ImageBitmap?
}

/** Lo que recibe `LedgerReviewScreen` en vez de `java.io.File`. */
data class LedgerCaptureRef(val path: String, val files: LedgerPageFiles)

interface VoicePermissionGate {
    fun isGranted(): Boolean
    fun request()
}

fun interface VoicePermissionGateFactory {
    @Composable
    fun remember(onResult: (Boolean) -> Unit): VoicePermissionGate
}
