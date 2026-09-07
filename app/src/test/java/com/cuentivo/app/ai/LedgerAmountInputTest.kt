package com.cuentivo.app.ai

import com.cuentivo.app.ai.ledger.LedgerAmountInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The conversion that stands between a paper price and a debt in someone's account.
 *
 * The case that started it: a notebook saying "$999" came back from the model as 9990 cents, and the
 * dialog asked the person to check "9990" against "999". Both directions are asserted here, because
 * a display that is right and a parse that is wrong loses exactly as much money.
 */
class LedgerAmountInputTest {

    @Test
    fun `cents are shown as the notebook writes them`() {
        assertEquals("999", LedgerAmountInput.toPesosText("99900"))
        assertEquals("99.90", LedgerAmountInput.toPesosText("9990"))
        assertEquals("0.05", LedgerAmountInput.toPesosText("5"))
        assertEquals("0.50", LedgerAmountInput.toPesosText("50"))
        assertEquals("1250", LedgerAmountInput.toPesosText("125000"))
    }

    @Test
    fun `a whole number of pesos does not lose two zeros`() {
        assertEquals("99900", LedgerAmountInput.toCents("999"))
        assertEquals("10000", LedgerAmountInput.toCents("100"))
        assertEquals("125000", LedgerAmountInput.toCents("1250"))
    }

    @Test
    fun `pesos with centavos convert exactly`() {
        assertEquals("9990", LedgerAmountInput.toCents("99.90"))
        assertEquals("9990", LedgerAmountInput.toCents("99.9"))
        assertEquals("5050", LedgerAmountInput.toCents("50.50"))
        assertEquals("5", LedgerAmountInput.toCents("0.05"))
    }

    @Test
    fun `what the person writes around a price does not break it`() {
        assertEquals("99900", LedgerAmountInput.toCents(" $999 "))
        assertEquals("125000", LedgerAmountInput.toCents("1,250"))
        // A comma with exactly two digits behind it is the decimal comma people also write.
        assertEquals("9990", LedgerAmountInput.toCents("99,90"))
        assertEquals("125050", LedgerAmountInput.toCents("1,250.50"))
    }

    @Test
    fun `anything that is not an amount is refused instead of guessed`() {
        assertNull(LedgerAmountInput.toCents(""))
        assertNull(LedgerAmountInput.toCents("   "))
        assertNull(LedgerAmountInput.toCents("abc"))
        assertNull(LedgerAmountInput.toCents("99.999"))
        assertNull(LedgerAmountInput.toCents("-99"))
        assertNull(LedgerAmountInput.toCents("99.90.10"))
    }

    @Test
    fun `an amount too large to hold is refused, not wrapped`() {
        assertNull(LedgerAmountInput.toCents("99999999999999999999"))
    }

    @Test
    fun `the round trip is exact, so opening a row and saving it changes nothing`() {
        listOf("1", "5", "50", "9990", "99900", "125000", "100000000").forEach { cents ->
            assertEquals(cents, LedgerAmountInput.toCents(LedgerAmountInput.toPesosText(cents)))
        }
    }
}
