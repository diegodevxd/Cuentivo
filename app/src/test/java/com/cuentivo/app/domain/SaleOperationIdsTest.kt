package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SaleOperationIdsTest {

    @Test
    fun `deterministic operation IDs generated for same key and parameters`() {
        val key = "test-key-1"
        assertEquals(SaleOperationIds.sale(key), SaleOperationIds.sale(key))
        assertEquals(SaleOperationIds.chargeMovement(key), SaleOperationIds.chargeMovement(key))
        assertEquals(SaleOperationIds.payment(key), SaleOperationIds.payment(key))
        assertEquals(SaleOperationIds.paymentMovement(key), SaleOperationIds.paymentMovement(key))
        assertEquals(SaleOperationIds.saleItem(key, "prod-1", 0), SaleOperationIds.saleItem(key, "prod-1", 0))
        assertEquals(SaleOperationIds.uncatalogedSaleItem(key, 0), SaleOperationIds.uncatalogedSaleItem(key, 0))
        assertEquals(SaleOperationIds.allocation(key, "sale-1"), SaleOperationIds.allocation(key, "sale-1"))
    }

    @Test
    fun `uncatalogedSaleItem never collides with saleItem for any standard or custom product id`() {
        val key = "test-key-collision"
        val uncataloged0 = SaleOperationIds.uncatalogedSaleItem(key, 0)
        val uncataloged1 = SaleOperationIds.uncatalogedSaleItem(key, 1)

        assertNotEquals(uncataloged0, uncataloged1)

        val candidateProductIds = listOf(
            "uncataloged",
            ":uncataloged",
            "custom",
            "prod-1",
            "null",
            "",
            "item-0",
            "0",
        )

        for (productId in candidateProductIds) {
            val catalogItemId = SaleOperationIds.saleItem(key, productId, 0)
            assertNotEquals(
                "uncatalogedSaleItem(0) must not collide with saleItem('$productId', 0)",
                uncataloged0,
                catalogItemId,
            )
        }
    }
}
