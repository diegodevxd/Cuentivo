package com.cuentivo.app.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LedgerScreenFormatTest {

    @Test
    fun `format cents formats zero`() {
        assertEquals("$0.00", formatCents(0L))
    }

    @Test
    fun `format cents formats five cents`() {
        assertEquals("$0.05", formatCents(5L))
    }

    @Test
    fun `format cents formats negative five cents`() {
        assertEquals("$-0.05", formatCents(-5L))
    }

    @Test
    fun `format cents absolute ignores sign`() {
        assertEquals("$0.05", formatCents(-5L, absolute = true))
    }

    @Test
    fun `format cents formats one thousand two hundred thirty four pesos`() {
        assertEquals("$1234.56", formatCents(123456L))
    }

    @Test
    fun `format cents handles long min absolute`() {
        assertEquals("$92233720368547758.08", formatCents(Long.MIN_VALUE, absolute = true))
    }

    @Test
    fun `format cents handles long min`() {
        assertEquals("$-92233720368547758.08", formatCents(Long.MIN_VALUE))
    }

    @Test
    fun `encode ledger image encodes man`() {
        assertEquals("TWFu", encodeLedgerImage("Man".encodeToByteArray()))
    }

    @Test
    fun `encode ledger image pads two bytes`() {
        assertEquals("TWE=", encodeLedgerImage("Ma".encodeToByteArray()))
    }

    @Test
    fun `encode ledger image has no newlines`() {
        val encoded = encodeLedgerImage(ByteArray(120) { it.toByte() })
        assertFalse(encoded.contains('\n'))
    }

    @Test
    fun `ledger iso date pads month and day`() {
        assertEquals("2026-01-05", ledgerIsoDate(2026, 1, 5))
    }

    @Test
    fun `ledger iso date formats end of year`() {
        assertEquals("2026-12-31", ledgerIsoDate(2026, 12, 31))
    }
}
