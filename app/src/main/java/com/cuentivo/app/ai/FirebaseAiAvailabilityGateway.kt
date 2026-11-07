package com.cuentivo.app.ai

import com.cuentivo.app.FUNCTIONS_REGION
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Transport that queries remote AI availability from the regional callable.
 *
 * Fail-open design (deliberate):
 * If the network fails, times out, or the callable throws, this gateway returns null
 * so [AiAvailabilityPolicy.resolve] preserves the existing state / opens to true.
 * The server-side authorization remains authoritative and fails closed.
 */
class FirebaseAiAvailabilityGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION),
) : AiAvailabilityGateway {

    override suspend fun checkAvailability(): Boolean? {
        return try {
            withContext(Dispatchers.IO) {
                val task = functions.getHttpsCallable(AI_AVAILABILITY_CALLABLE_NAME).call()
                parseAvailabilityResponse(Tasks.await(task).data)
            }
        } catch (_: Exception) {
            // Returns null on network error / timeout to preserve known state and fail open
            null
        }
    }
}
