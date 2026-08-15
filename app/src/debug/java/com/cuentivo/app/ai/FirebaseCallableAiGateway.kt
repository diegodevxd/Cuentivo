package com.cuentivo.app.ai

import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions

/**
 * Debug-only callable transport to the `us-south1` gateway.
 *
 * Deliberately not wired to any UI, ViewModel, Room or productive [AiProvider]. The v1 contract
 * carries no text, and every payload shape collapses to [AiProviderResult.Unavailable] with the
 * fixed technical reason; this transport can never produce an [AiIntent] or an [AiProviderResult.Interpreted].
 */

private const val CALLABLE_NAME = "interpretAiRequest"

private const val REGION = "us-south1"

private const val NOT_CONFIGURED = "AI_NOT_CONFIGURED"

/**
 * Pure parser for the callable response. Accepts only the exact v1 response map — contractVersion 1,
 * the expected requestId and status AI_NOT_CONFIGURED — and collapses it, and everything else, to
 * [AiProviderResult.Unavailable]. Never returns an interpreted intent.
 */
internal fun parseCallableData(data: Any?, expectedRequestId: String): AiProviderResult {
    val map = data as? Map<*, *> ?: return AiProviderResult.Unavailable(NOT_CONFIGURED)
    if (map.keys.map { it.toString() }.toSet() != setOf("contractVersion", "requestId", "status")) {
        return AiProviderResult.Unavailable(NOT_CONFIGURED)
    }
    if ((map["contractVersion"] as? Number)?.toLong() != 1L) {
        return AiProviderResult.Unavailable(NOT_CONFIGURED)
    }
    if (map["requestId"] != expectedRequestId) {
        return AiProviderResult.Unavailable(NOT_CONFIGURED)
    }
    if (map["status"] != NOT_CONFIGURED) {
        return AiProviderResult.Unavailable(NOT_CONFIGURED)
    }
    return AiProviderResult.Unavailable(NOT_CONFIGURED)
}

/**
 * Transport that calls the regional callable with the exact v1 request map (no text) and parses
 * the response with [parseCallableData]. Suspension uses the existing approved Tasks bridge only.
 */
class FirebaseCallableAiGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(REGION),
) {

    suspend fun callContractV1(requestId: String): AiProviderResult {
        val payload = mapOf(
            "contractVersion" to 1,
            "requestId" to requestId,
            "action" to "interpret_text",
        )
        return try {
            val task = functions.getHttpsCallable(CALLABLE_NAME).call(payload)
            parseCallableData(Tasks.await(task).data, requestId)
        } catch (_: Exception) {
            AiProviderResult.Unavailable(NOT_CONFIGURED)
        }
    }
}
