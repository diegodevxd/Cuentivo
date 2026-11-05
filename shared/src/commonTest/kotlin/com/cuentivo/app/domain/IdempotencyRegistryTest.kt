package com.cuentivo.app.domain

import kotlin.test.assertEquals
import kotlin.test.Test

class IdempotencyRegistryTest {
    @Test
    fun `returns the original result when the same operation key is repeated`() {
        val registry = IdempotencyRegistry<String>()
        val first = registry.execute("payment-1") { "created-payment" }
        val repeated = registry.execute("payment-1") { "duplicate-payment" }

        assertEquals("created-payment", first)
        assertEquals("created-payment", repeated)
    }
}
