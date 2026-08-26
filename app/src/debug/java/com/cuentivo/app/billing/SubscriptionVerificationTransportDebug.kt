package com.cuentivo.app.billing

import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug implementation of [SubscriptionVerificationGateway] backed by the regional
 * `us-south1` `verifyAiSubscription` Firebase Callable.
 */
class FirebaseCallableSubscriptionVerificationGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("us-south1"),
) : SubscriptionVerificationGateway {

    override fun verifySubscription(
        purchaseToken: String,
        productId: String,
        onComplete: (Result<SubscriptionVerificationResult>) -> Unit,
    ) {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val payload = mapOf(
                    "purchaseToken" to purchaseToken,
                    "productId" to productId,
                )
                val result = withContext(Dispatchers.IO) {
                    val task = functions.getHttpsCallable("verifyAiSubscription").call(payload)
                    val data = Tasks.await(task).data as? Map<*, *>
                    val status = data?.get("status") as? String ?: "UNKNOWN"
                    val enabled = data?.get("enabled") as? Boolean ?: false
                    val expiresAt = (data?.get("expiresAtEpochMillis") as? Number)?.toLong() ?: 0L
                    SubscriptionVerificationResult(status, enabled, expiresAt)
                }
                onComplete(Result.success(result))
            } catch (e: Exception) {
                onComplete(Result.failure(e))
            }
        }
    }
}

/**
 * Debug source-set entry point returning the real regional callable transport.
 */
fun subscriptionVerificationGateway(): SubscriptionVerificationGateway =
    FirebaseCallableSubscriptionVerificationGateway()
