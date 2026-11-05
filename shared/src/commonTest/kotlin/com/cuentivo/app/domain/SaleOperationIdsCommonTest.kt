package com.cuentivo.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Igual que en pagos: el id de venta se persiste y es lo que hace idempotente un reintento. El
 * esperado sale del JDK, no de este código.
 */
class SaleOperationIdsCommonTest {

    @Test
    fun sale_id_matches_the_value_java_util_uuid_produced() {
        // nameUUIDFromBytes("test-key-1:sale:root")
        assertEquals("7d87d97a-fd41-369f-9427-ab791fe9ba0e", SaleOperationIds.sale("test-key-1"))
    }

    @Test
    fun each_kind_keeps_its_own_id() {
        val key = "test-key-1"
        assertEquals(SaleOperationIds.sale(key), SaleOperationIds.sale(key))
        assertEquals(7, setOf(
            SaleOperationIds.sale(key),
            SaleOperationIds.chargeMovement(key),
            SaleOperationIds.payment(key),
            SaleOperationIds.paymentMovement(key),
            SaleOperationIds.saleItem(key, "prod-1", 0),
            SaleOperationIds.uncatalogedSaleItem(key, 0),
            SaleOperationIds.allocation(key, "sale-1"),
        ).size)
    }
}
