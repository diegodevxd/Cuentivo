package com.cuentivo.app.ai.ledger

import com.cuentivo.app.FUNCTIONS_REGION
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Calls the regional callable with the exact v3 payload. Every failure collapses to
 * [LedgerExtractionCallResult.Unavailable] without logging the image or anything read from it.
 */
class LedgerExtractionGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION),
) {
    suspend fun extractPage(
        requestId: String,
        imageBase64: String,
        imageMimeType: String,
    ): LedgerExtractionCallResult =
        extractPageWithFallback { version -> callRemote(requestId, imageBase64, imageMimeType, version) }

    private suspend fun callRemote(requestId: String, imageBase64: String, imageMimeType: String, version: Int): LedgerExtractionCallResult {
        val payload = buildLedgerCallablePayload(requestId, imageBase64, imageMimeType).toMutableMap().apply { put("contractVersion", version) }
        return try {
            withContext(Dispatchers.IO) {
                val task = functions.getHttpsCallable(LEDGER_CALLABLE_NAME).call(payload)
                parseLedgerExtractionResponse(Tasks.await(task).data, requestId)
            }
        } catch (_: Exception) {
            LedgerExtractionCallResult.Unavailable
        }
    }
}
