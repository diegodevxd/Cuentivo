package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PaymentOperationIdsTest {

    @Test
    fun `same inputs produce identical deterministic ids`() {
        val key = "ai-confirm-123"
        assertEquals(PaymentOperationIds.payment(key), PaymentOperationIds.payment(key))
        assertEquals(PaymentOperationIds.movement(key), PaymentOperationIds.movement(key))
        assertEquals(PaymentOperationIds.allocation(key, "sale-1"), PaymentOperationIds.allocation(key, "sale-1"))
    }

    @Test
    fun `different keys produce distinct ids`() {
        assertNotEquals(PaymentOperationIds.payment("key-1"), PaymentOperationIds.payment("key-2"))
        assertNotEquals(PaymentOperationIds.movement("key-1"), PaymentOperationIds.movement("key-2"))
        assertNotEquals(PaymentOperationIds.allocation("key-1", "s1"), PaymentOperationIds.allocation("key-2", "s1"))
    }

    @Test
    fun `different entity kinds produce distinct ids for the same key`() {
        val key = "ai-confirm-123"
        val paymentId = PaymentOperationIds.payment(key)
        val movementId = PaymentOperationIds.movement(key)
        val allocationId = PaymentOperationIds.allocation(key, "root")

        assertNotEquals(paymentId, movementId)
        assertNotEquals(paymentId, allocationId)
        assertNotEquals(movementId, allocationId)
    }

    @Test
    fun `different sale ids produce distinct allocation ids`() {
        val key = "ai-confirm-123"
        assertNotEquals(
            PaymentOperationIds.allocation(key, "sale-a"),
            PaymentOperationIds.allocation(key, "sale-b"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank key throws on payment id generation`() {
        PaymentOperationIds.payment("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank key throws on movement id generation`() {
        PaymentOperationIds.movement("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank key throws on allocation id generation`() {
        PaymentOperationIds.allocation("", "sale-1")
    }
}
