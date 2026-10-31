package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backoff that keeps a dropped Play connection from becoming a permanently broken paywall.
 */
class BillingReconnectPolicyTest {

    @Test
    fun `the first retry is quick enough to be invisible mid-purchase`() {
        assertEquals(BillingReconnectPolicy.FIRST_DELAY_MILLIS, BillingReconnectPolicy.delayMillisForAttempt(1))
    }

    @Test
    fun `each attempt waits twice as long as the last, until the cap`() {
        val delays = (1..BillingReconnectPolicy.MAX_ATTEMPTS)
            .map { BillingReconnectPolicy.delayMillisForAttempt(it) }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), delays)
    }

    @Test
    fun `no delay ever exceeds the cap`() {
        // The doubling is what makes this worth asserting rather than eyeballing: an unclamped
        // shift reaches minutes within a couple of attempts.
        (1..BillingReconnectPolicy.MAX_ATTEMPTS).forEach { attempt ->
            val delay = requireNotNull(BillingReconnectPolicy.delayMillisForAttempt(attempt))
            assertTrue("attempt $attempt waited $delay", delay <= BillingReconnectPolicy.MAX_DELAY_MILLIS)
            assertTrue("attempt $attempt waited $delay", delay > 0)
        }
    }

    @Test
    fun `giving up is explicit once the attempts run out`() {
        // Null rather than a huge delay: the gateway must be able to tell "wait" from "stop", or a
        // phone without Play services keeps a timer alive for nothing.
        assertNull(BillingReconnectPolicy.delayMillisForAttempt(BillingReconnectPolicy.MAX_ATTEMPTS + 1))
        assertNull(BillingReconnectPolicy.delayMillisForAttempt(99))
    }

    @Test
    fun `an attempt number below the first is refused rather than treated as the first`() {
        // A caller that forgot to increment would otherwise retry instantly and forever.
        assertNull(BillingReconnectPolicy.delayMillisForAttempt(0))
        assertNull(BillingReconnectPolicy.delayMillisForAttempt(-1))
    }
}
