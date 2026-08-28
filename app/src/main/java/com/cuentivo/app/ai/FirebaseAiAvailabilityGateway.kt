package com.cuentivo.app.ai

import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val AVAILABILITY_CALLABLE_NAME = "getAiAvailability"
private const val REGION = "us-south1"

/**
 * Parses the raw callable response. Returns true if available or if data is null/malformed (fail open).
 * Returns false strictly when data contains `available == false`.
 */
internal fun parseAvailabilityResponse(data: Any?): Boolean {
    val map = data as? Map<*, *> ?: return true
    return (map["available"] as? Boolean) ?: true
}

/**
 * Transport that queries remote AI availability from the regional callable.
 *
 * Fail-open design (deliberate):
 * If the network fails, times out, or the callable throws, this gateway returns true.
 * The server-side authorization remains authoritative and fails closed.
 * Failing closed in the client would turn temporary network glitches into perceived feature loss for paying subscribers.
 */
class FirebaseAiAvailabilityGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(REGION),
) : AiAvailabilityGateway {

    override suspend fun checkAvailability(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val task = functions.getHttpsCallable(AVAILABILITY_CALLABLE_NAME).call()
                parseAvailabilityResponse(Tasks.await(task).data)
            }
        } catch (_: Exception) {
            // Fail open: a transient network glitch must not hide paid functionality.
            // Server-side authorization remains authoritative.
            true
        }
    }
}
