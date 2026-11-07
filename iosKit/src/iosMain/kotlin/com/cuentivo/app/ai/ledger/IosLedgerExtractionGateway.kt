package com.cuentivo.app.ai.ledger

import com.cuentivo.app.ai.AI_FUNCTIONS_REGION
import com.cuentivo.app.sync.normalizeFirestoreValue
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.FirebaseFunctions
import dev.gitlive.firebase.functions.functions
import dev.gitlive.firebase.functions.ios
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Lectura de una página de libreta en iOS. Mismo payload, parser y reintento único que Android. Los
 * enteros no pasan por `Double`: la respuesta se lee del objeto nativo y se normaliza. Todo fallo
 * colapsa a Unavailable, sin registrar la imagen ni nada leído de ella.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLedgerExtractionGateway(
    private val functions: FirebaseFunctions = Firebase.functions(AI_FUNCTIONS_REGION),
) {
    suspend fun extractPage(
        requestId: String,
        imageBase64: String,
        imageMimeType: String,
    ): LedgerExtractionCallResult =
        extractPageWithFallback { version -> callRemote(requestId, imageBase64, imageMimeType, version) }

    private suspend fun callRemote(
        requestId: String,
        imageBase64: String,
        imageMimeType: String,
        version: Int,
    ): LedgerExtractionCallResult = try {
        val payload: Map<String, Any> =
            buildLedgerCallablePayload(requestId, imageBase64, imageMimeType) + ("contractVersion" to version)
        val result = functions.httpsCallable(LEDGER_CALLABLE_NAME, null).invoke(payload)
        parseLedgerExtractionResponse(normalizeFirestoreValue(result.ios.data()), requestId)
    } catch (_: Throwable) {
        LedgerExtractionCallResult.Unavailable
    }
}
