package com.cuentivo.app.ai.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MathExactTest {

    @Test
    fun `multiply exact rejects minus one by long min`() {
        assertFailsWith<ArithmeticException> { multiplyExact(-1L, Long.MIN_VALUE) }
    }

    @Test
    fun `multiply exact rejects long min by minus one`() {
        assertFailsWith<ArithmeticException> { multiplyExact(Long.MIN_VALUE, -1L) }
    }

    @Test
    fun `multiply exact multiplies three by four`() {
        assertEquals(12L, multiplyExact(3L, 4L))
    }
}
