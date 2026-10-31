package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyMigrationPlannerTest {
    @Test fun `maps legacy pedidos and pagos while preserving the original movement ids`() {
        val plan = LegacyMigrationPlanner.plan(
            businessId = "business-1",
            ownerUserId = "owner-1",
            movements = listOf(
                LegacyMovimiento(10, "Ana L?pez", "pedido", 1_000, "Cat?logo", 10, "pagado"),
                LegacyMovimiento(11, "Ana L?pez", "pago", 200, "Abono", 11, null),
            ),
        )

        assertEquals(2, plan.acceptedMovements)
        assertEquals(1, plan.customers.size)
        assertEquals("legacy-movimiento-10", plan.sales.single().id)
        assertEquals(2, plan.payments.size)
        val balance = plan.accountMovements.sumOf { if (it.direction == "DEBIT") it.amountCents else -it.amountCents }
        assertEquals(-200, balance)
    }
}
