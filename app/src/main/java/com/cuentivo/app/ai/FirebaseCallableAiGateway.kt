package com.cuentivo.app.ai

import com.cuentivo.app.FUNCTIONS_REGION
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Regional callable transport to the `us-south1` `interpretAiRequest` gateway (v2 typed
 * proposal contract). Not wired to any ViewModel, Room or productive financial mutator; it
 * performs no execution and exposes no error messages, ids, balances or raw text.
 */

private const val CALLABLE_NAME = AI_CALLABLE_NAME

private const val CONTRACT_VERSION_FIELD = AI_CONTRACT_VERSION_FIELD

private const val ACTION_VALUE = AI_ACTION_VALUE

/** Exact v2 request payload: contractVersion 2, requestId, action and the original text. */
internal fun buildCallablePayload2(request: AiInterpretRequest): Map<String, Any> = mapOf(
    "contractVersion" to CONTRACT_VERSION_FIELD,
    "requestId" to request.requestId,
    "action" to ACTION_VALUE,
    "text" to request.text,
)

/**
 * Transport that calls the regional callable with the exact v2 payload and strictly
 * parses the v2 response. Suspension uses the existing approved Tasks bridge only; every failure
 * (call error, timeout, unexpected shape, malformed data) collapses to
 * [CallableAiProposalResult.Unavailable] without logs.
 */
class FirebaseCallableAiGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION),
) {

    suspend fun callContractV2(request: AiInterpretRequest): CallableAiProposalResult {
        val payload = buildCallablePayload2(request)
        return try {
            withContext(Dispatchers.IO) {
                val task = functions.getHttpsCallable(CALLABLE_NAME).call(payload)
                parseCallableProposalResponse(Tasks.await(task).data, request.requestId)
            }
        } catch (_: Exception) {
            CallableAiProposalResult.Unavailable
        }
    }
}
