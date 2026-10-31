package com.cuentivo.app.ai

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.ExpectedBalancePaymentResult
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.StoredPaymentBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPaymentExecutionTest {

    private val validRequest = AiPaymentConfirmationRequest(
        confirmationKey = "confirm-key-1",
        businessId = "biz-1",
        customerId = "cust-1",
        expectedBalanceCents = 50_000L,
        amountCents = 20_000L,
    )

    private fun sampleBundle(paymentId: String = "pay-1") = StoredPaymentBundle(
        payment = PaymentEntity(
            id = paymentId,
            businessId = "biz-1",
            customerId = "cust-1",
            amountCents = 20_000L,
            paymentMethod = "CASH",
            paymentDateEpochDay = 20_000L,
            reference = null,
            notes = null,
            createdByUserId = "user-1",
            createdAtEpochMillis = 10_000L,
            canceledAtEpochMillis = null,
        ),
        allocations = listOf(
            PaymentAllocationEntity(
                id = "alloc-1",
                paymentId = paymentId,
                saleId = "sale-1",
                amountCents = 20_000L,
            ),
        ),
        movement = AccountMovementEntity(
            id = "mov-1",
            businessId = "biz-1",
            customerId = "cust-1",
            saleId = null,
            paymentId = paymentId,
            type = "PAYMENT",
            amountCents = 20_000L,
            direction = "CREDIT",
            occurredAtEpochMillis = 10_000L,
            createdByUserId = "user-1",
            reversesMovementId = null,
            notes = null,
        ),
    )

    @Test
    fun `valid request delegates exact values and enqueues stored bundle on success`() = runBlocking {
        var passedIdempotencyKey: String? = null
        var passedPayment: PaymentEntity? = null
        var passedMovement: AccountMovementEntity? = null
        var passedExpectedBalance: Long? = null
        var enqueuedBundle: StoredPaymentBundle? = null
        var loadedPaymentId: String? = null

        val service = AiPaymentExecutionService(
            registerPayment = { key, pay, mov, expected ->
                passedIdempotencyKey = key
                passedPayment = pay
                passedMovement = mov
                passedExpectedBalance = expected
                ExpectedBalancePaymentResult.Recorded(pay.id)
            },
            loadStoredBundle = { bId, pId ->
                loadedPaymentId = pId
                sampleBundle(pId)
            },
            enqueueStoredBundle = { bundle ->
                enqueuedBundle = bundle
            },
            currentUserId = { "user-123" },
            nowMillis = { 1700000000000L },
            todayEpochDay = { 20_100L },
        )

        val result = service.confirm(validRequest)

        assertTrue(result is AiPaymentExecutionResult.Recorded)
        val recorded = result as AiPaymentExecutionResult.Recorded
        assertEquals(passedPayment?.id, recorded.paymentId)
        assertEquals("ai-payment-confirm-key-1", passedIdempotencyKey)
        assertEquals(50_000L, passedExpectedBalance)
        assertEquals("biz-1", passedPayment?.businessId)
        assertEquals("cust-1", passedPayment?.customerId)
        assertEquals(20_000L, passedPayment?.amountCents)
        assertEquals("user-123", passedPayment?.createdByUserId)
        assertEquals(1700000000000L, passedPayment?.createdAtEpochMillis)
        assertEquals(20_100L, passedPayment?.paymentDateEpochDay)
        assertEquals("PAYMENT", passedMovement?.type)
        assertEquals("CREDIT", passedMovement?.direction)
        assertEquals(recorded.paymentId, loadedPaymentId)
        assertEquals(sampleBundle(recorded.paymentId), enqueuedBundle)
    }

    @Test
    fun `stale balance returns StalePreview without enqueuing sync`() = runBlocking {
        var enqueued = false
        val service = AiPaymentExecutionService(
            registerPayment = { _, _, _, _ -> ExpectedBalancePaymentResult.StaleBalance },
            loadStoredBundle = { _, _ -> sampleBundle() },
            enqueueStoredBundle = { enqueued = true },
            currentUserId = { "user-1" },
            nowMillis = { 1000L },
            todayEpochDay = { 20000L },
        )

        val result = service.confirm(validRequest)

        assertEquals(AiPaymentExecutionResult.StalePreview, result)
        assertEquals(false, enqueued)
    }

    @Test
    fun `invalid request parameters are rejected without calling writer`() = runBlocking {
        var writerCalled = false
        val service = AiPaymentExecutionService(
            registerPayment = { _, _, _, _ -> writerCalled = true; ExpectedBalancePaymentResult.Recorded("p1") },
            loadStoredBundle = { _, _ -> null },
            enqueueStoredBundle = {},
            currentUserId = { "user-1" },
            nowMillis = { 1000L },
            todayEpochDay = { 20000L },
        )

        val invalidRequests = listOf(
            validRequest.copy(confirmationKey = ""),
            validRequest.copy(confirmationKey = "   "),
            validRequest.copy(businessId = ""),
            validRequest.copy(customerId = ""),
            validRequest.copy(expectedBalanceCents = 0L),
            validRequest.copy(expectedBalanceCents = -100L),
            validRequest.copy(amountCents = 0L),
            validRequest.copy(amountCents = -50L),
            validRequest.copy(amountCents = 60_000L, expectedBalanceCents = 50_000L), // amount > balance
        )

        for (invalid in invalidRequests) {
            writerCalled = false
            val result = service.confirm(invalid)
            assertEquals("Expected Rejected for $invalid", AiPaymentExecutionResult.Rejected, result)
            assertEquals("Writer must not be called for $invalid", false, writerCalled)
        }
    }

    @Test
    fun `blank currentUserId returns Unavailable without calling writer`() = runBlocking {
        var writerCalled = false
        val service = AiPaymentExecutionService(
            registerPayment = { _, _, _, _ -> writerCalled = true; ExpectedBalancePaymentResult.Recorded("p1") },
            loadStoredBundle = { _, _ -> null },
            enqueueStoredBundle = {},
            currentUserId = { "   " },
            nowMillis = { 1000L },
            todayEpochDay = { 20000L },
        )

        val result = service.confirm(validRequest)
        assertEquals(AiPaymentExecutionResult.Unavailable, result)
        assertEquals(false, writerCalled)
    }

    @Test
    fun `cancellation exception is rethrown and not swallowed`() = runBlocking {
        val service = AiPaymentExecutionService(
            registerPayment = { _, _, _, _ -> throw CancellationException("cancelled") },
            loadStoredBundle = { _, _ -> null },
            enqueueStoredBundle = {},
            currentUserId = { "user-1" },
            nowMillis = { 1000L },
            todayEpochDay = { 20000L },
        )

        try {
            service.confirm(validRequest)
            throw AssertionError("expected CancellationException")
        } catch (expected: CancellationException) {
            // expected
        }
    }

    @Test
    fun `unexpected exceptions map to Unavailable`() = runBlocking {
        val service = AiPaymentExecutionService(
            registerPayment = { _, _, _, _ -> throw IllegalStateException("db disk error") },
            loadStoredBundle = { _, _ -> null },
            enqueueStoredBundle = {},
            currentUserId = { "user-1" },
            nowMillis = { 1000L },
            todayEpochDay = { 20000L },
        )

        val result = service.confirm(validRequest)
        assertEquals(AiPaymentExecutionResult.Unavailable, result)
    }
}
