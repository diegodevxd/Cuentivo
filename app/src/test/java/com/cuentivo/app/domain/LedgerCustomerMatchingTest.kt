package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.UnreadableLedgerField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app offers a customer, and when it keeps quiet.
 *
 * Offering too eagerly is not a cosmetic problem: every offer is an invitation to attach somebody
 * else's money to a name, and the person tapping through twenty rows will accept what looks right.
 */
class LedgerCustomerMatchingTest {

    private val rosa = CustomerCandidate("cust-rosa", "Rosa Martinez")
    private val mayra = CustomerCandidate("cust-mayra", "Mayra T5")

    private fun op(
        lineIndex: Int,
        name: String?,
        customerId: String? = null,
        unreadable: List<UnreadableLedgerField> = emptyList(),
    ) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = "1000",
        customerId = customerId,
        unreadableFields = unreadable,
    )

    @Test
    fun `a partial name finds the customer it resembles`() {
        val matches = LedgerCustomerMatching.matchesFor(listOf(op(0, "Rosa Martnez")), listOf(rosa, mayra))

        assertEquals(rosa, matches[0]?.candidate)
    }

    @Test
    fun `a row already anchored is never asked again`() {
        // The person already decided. Offering a guess on top of it invites them to overwrite it.
        val matches = LedgerCustomerMatching.matchesFor(
            listOf(op(0, "Rosa Martnez", customerId = "cust-otro")),
            listOf(rosa, mayra),
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `a row whose customer could not be read gets no candidate at all`() {
        // There is no text to match against, so any suggestion would be a name invented out of an
        // empty field.
        val matches = LedgerCustomerMatching.matchesFor(
            listOf(op(0, null, unreadable = listOf(UnreadableLedgerField.CUSTOMER))),
            listOf(rosa, mayra),
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `an exact name is not offered, because there is nothing to decide`() {
        val matches = LedgerCustomerMatching.matchesFor(listOf(op(0, "Rosa Martinez")), listOf(rosa))

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `a name that resembles nobody is left alone`() {
        val matches = LedgerCustomerMatching.matchesFor(listOf(op(0, "Xochitl")), listOf(rosa, mayra))

        assertNull(matches[0])
    }

    @Test
    fun `an empty customer list offers nothing instead of failing`() {
        assertTrue(LedgerCustomerMatching.matchesFor(listOf(op(0, "Rosa")), emptyList()).isEmpty())
    }

    @Test
    fun `matching never touches the operations it was given`() {
        // It reports; it does not decide. Anchoring is a separate, explicit act by a person.
        val operations = listOf(op(0, "Rosa Martnez"))

        LedgerCustomerMatching.matchesFor(operations, listOf(rosa))

        assertNull(operations[0].customerId)
        assertEquals("Rosa Martnez", operations[0].customerName)
        assertFalse(operations[0].customerName == rosa.name)
    }
}
