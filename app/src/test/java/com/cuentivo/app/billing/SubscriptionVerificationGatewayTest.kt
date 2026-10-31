package com.cuentivo.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionVerificationGatewayTest {

    @Test
    fun verificationResult_holds_expected_fields() {
        val result = SubscriptionVerificationResult(
            status = "VERIFIED",
            enabled = true,
            expiresAtEpochMillis = 1756200000000L,
        )
        assertEquals("VERIFIED", result.status)
        assertTrue(result.enabled)
        assertEquals(1756200000000L, result.expiresAtEpochMillis)
    }

    @Test
    fun verificationResult_disabled_entitlement() {
        val result = SubscriptionVerificationResult(
            status = "INACTIVE",
            enabled = false,
            expiresAtEpochMillis = 0L,
        )
        assertEquals("INACTIVE", result.status)
        assertFalse(result.enabled)
        assertEquals(0L, result.expiresAtEpochMillis)
    }

    @Test
    fun mockGateway_dispatches_success_result() {
        var completedResult: Result<SubscriptionVerificationResult>? = null
        val gateway = object : SubscriptionVerificationGateway {
            override fun verifySubscription(
                purchaseToken: String,
                productId: String,
                onComplete: (Result<SubscriptionVerificationResult>) -> Unit,
            ) {
                onComplete(
                    Result.success(
                        SubscriptionVerificationResult(
                            status = "VERIFIED",
                            enabled = true,
                            expiresAtEpochMillis = 1756200000000L,
                        ),
                    ),
                )
            }
        }

        gateway.verifySubscription("token_123", "cuentivo_ai_mensual") { res ->
            completedResult = res
        }

        assertTrue(completedResult!!.isSuccess)
        assertEquals("VERIFIED", completedResult!!.getOrNull()?.status)
        assertTrue(completedResult!!.getOrNull()?.enabled == true)
    }

    @Test
    fun mockGateway_dispatches_failure_without_granting_entitlement() {
        var completedResult: Result<SubscriptionVerificationResult>? = null
        val gateway = object : SubscriptionVerificationGateway {
            override fun verifySubscription(
                purchaseToken: String,
                productId: String,
                onComplete: (Result<SubscriptionVerificationResult>) -> Unit,
            ) {
                onComplete(Result.failure(RuntimeException("Network error")))
            }
        }

        gateway.verifySubscription("token_123", "cuentivo_ai_mensual") { res ->
            completedResult = res
        }

        assertTrue(completedResult!!.isFailure)
    }
}
