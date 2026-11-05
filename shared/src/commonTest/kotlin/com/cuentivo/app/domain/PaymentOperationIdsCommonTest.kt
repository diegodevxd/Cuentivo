package com.cuentivo.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * El id de una operación se guarda en `operations` y es lo que hace idempotente un reintento; si
 * cambiara de valor, un mismo cobro podría aplicarse dos veces. Los esperados salen del JDK
 * (`java.util.UUID.nameUUIDFromBytes`), no de este código.
 */
class PaymentOperationIdsCommonTest {

    @Test
    fun ids_match_the_values_java_util_uuid_produced() {
        // nameUUIDFromBytes("ai-confirm-123:payment:root")
        assertEquals("95ab5559-d827-3405-a7a2-92c23808a8ea", PaymentOperationIds.payment("ai-confirm-123"))
        // nameUUIDFromBytes("ai-confirm-123:allocation:sale-1")
        assertEquals(
            "63eeb6e1-04e9-38a5-87c3-30ca2580302d",
            PaymentOperationIds.allocation("ai-confirm-123", "sale-1"),
        )
    }
}
