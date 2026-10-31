package com.cuentivo.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProposalSafetyTest {

    private fun line(
        productName: String = "Perfume A",
        quantity: Int = 2,
        unitPriceCents: Long = 30000L,
    ): AiProposalSaleLine = AiProposalSaleLine(productName, quantity, unitPriceCents)

    private fun sale(
        customerName: String = "María",
        lines: List<AiProposalSaleLine> = listOf(line()),
        declaredInitialPaymentCents: Long = 20000L,
        promisedPaymentCandidate: PromisedPaymentCandidate? = null,
    ): CreateSaleProposal = CreateSaleProposal(
        customerName,
        lines,
        declaredInitialPaymentCents,
        promisedPaymentCandidate,
    )

    private fun validPayment(amountCents: Long = 15000L): RecordPaymentProposal =
        RecordPaymentProposal("María", amountCents)

    private fun accepted(proposal: AiProposal): Boolean =
        SafeAiProposalValidator.validate(proposal) is AiProposalValidationResult.Accepted

    private fun rejectedReason(proposal: AiProposal): AiProposalRejectReason {
        val result = SafeAiProposalValidator.validate(proposal)
        assertTrue("expected rejection, got $result", result is AiProposalValidationResult.Rejected)
        return (result as AiProposalValidationResult.Rejected).reason
    }

    // ---- CreateSaleProposal ----

    @Test
    fun `valid sale is accepted and keeps its data`() {
        val proposal = sale(
            customerName = "María",
            lines = listOf(
                line("Perfume A", quantity = 2, unitPriceCents = 30000L),
                line("Cremas", quantity = 1, unitPriceCents = 15000L),
            ),
            declaredInitialPaymentCents = 20000L,
            promisedPaymentCandidate = PromisedPaymentCandidate.DateCandidate(2026, 8, 30),
        )
        val result = SafeAiProposalValidator.validate(proposal)
        assertTrue(result is AiProposalValidationResult.Accepted)
        result as AiProposalValidationResult.Accepted
        assertSame(proposal, result.proposal)
        assertEquals(1, proposal.schemaVersion)
        assertEquals("María", proposal.customerName)
        assertEquals(2, proposal.lines.size)
        assertEquals(30000L, proposal.lines[0].unitPriceCents)
        assertEquals(20000L, proposal.declaredInitialPaymentCents)
        assertEquals(PromisedPaymentCandidate.DateCandidate(2026, 8, 30), proposal.promisedPaymentCandidate)
    }

    @Test
    fun `valid sale exposes an immutable lines list`() {
        val proposal = sale()
        val lines = proposal.lines

        try {
            @Suppress("UNCHECKED_CAST")
            (lines as MutableList<AiProposalSaleLine>).add(line())
            throw AssertionError("lines must be unmodifiable")
        } catch (expected: UnsupportedOperationException) {
            // expected
        }

        try {
            @Suppress("UNCHECKED_CAST")
            (lines as MutableList<AiProposalSaleLine>).clear()
            throw AssertionError("lines must be unmodifiable")
        } catch (expected: UnsupportedOperationException) {
            // expected
        }
        assertEquals(1, proposal.lines.size)
    }

    @Test
    fun `sale with empty lines is rejected`() {
        assertEquals(AiProposalRejectReason.EMPTY_LINES, rejectedReason(sale(lines = emptyList())))
    }

    @Test
    fun `sale with blank customer name is rejected`() {
        assertEquals(AiProposalRejectReason.BLANK_CUSTOMER, rejectedReason(sale(customerName = "   ")))
    }

    @Test
    fun `sale with invalid line is rejected`() {
        assertEquals(AiProposalRejectReason.INVALID_LINE, rejectedReason(sale(lines = listOf(line(productName = "")))))
        assertEquals(AiProposalRejectReason.NEGATIVE_QUANTITY, rejectedReason(sale(lines = listOf(line(quantity = 0)))))
        assertEquals(AiProposalRejectReason.NEGATIVE_QUANTITY, rejectedReason(sale(lines = listOf(line(quantity = -3)))))
        assertEquals(AiProposalRejectReason.NEGATIVE_AMOUNT, rejectedReason(sale(lines = listOf(line(unitPriceCents = -1L)))))
    }

    @Test
    fun `sale with zero unit price line is accepted`() {
        assertTrue(accepted(sale(lines = listOf(line(unitPriceCents = 0L)))))
    }

    @Test
    fun `sale with zero initial payment is accepted and negative is rejected`() {
        assertTrue(accepted(sale(declaredInitialPaymentCents = 0L)))
        assertEquals(
            AiProposalRejectReason.NEGATIVE_AMOUNT,
            rejectedReason(sale(declaredInitialPaymentCents = -1L)),
        )
    }

    @Test
    fun `sale promise candidate with an invalid date is rejected`() {
        assertEquals(
            AiProposalRejectReason.INVALID_DATE,
            rejectedReason(sale(promisedPaymentCandidate = PromisedPaymentCandidate.DateCandidate(2026, 13, 1))),
        )
        assertEquals(
            AiProposalRejectReason.INVALID_DATE,
            rejectedReason(sale(promisedPaymentCandidate = PromisedPaymentCandidate.DateCandidate(2023, 2, 29))),
        )
        assertEquals(
            AiProposalRejectReason.INVALID_DATE,
            rejectedReason(sale(promisedPaymentCandidate = PromisedPaymentCandidate.DateCandidate(2026, 4, 31))),
        )
        assertTrue(accepted(sale(promisedPaymentCandidate = PromisedPaymentCandidate.DateCandidate(2024, 2, 29))))
    }

    // ---- RecordPaymentProposal ----

    @Test
    fun `payment zero is accepted and negative is rejected`() {
        assertTrue(accepted(validPayment(amountCents = 0L)))
        assertEquals(AiProposalRejectReason.NEGATIVE_AMOUNT, rejectedReason(validPayment(amountCents = -1L)))
    }

    @Test
    fun `payment with blank customer name is rejected`() {
        assertEquals(AiProposalRejectReason.BLANK_CUSTOMER, rejectedReason(RecordPaymentProposal(" ", 100L)))
    }

    // ---- BusinessQueryProposal ----

    @Test
    fun `balance query requires nonblank customer and no period`() {
        assertTrue(accepted(BusinessQueryProposal(BusinessQueryType.CUSTOMER_BALANCE, customerName = "María")))
        assertEquals(
            AiProposalRejectReason.INVALID_QUERY_SHAPE,
            rejectedReason(BusinessQueryProposal(BusinessQueryType.CUSTOMER_BALANCE, customerName = null)),
        )
        assertEquals(
            AiProposalRejectReason.INVALID_QUERY_SHAPE,
            rejectedReason(
                BusinessQueryProposal(
                    BusinessQueryType.CUSTOMER_BALANCE,
                    customerName = "María",
                    period = AiPeriodCandidate(2026, 8, 1, 2026, 8, 31),
                ),
            ),
        )
    }

    @Test
    fun `customers with debt query requires no selectors`() {
        assertTrue(accepted(BusinessQueryProposal(BusinessQueryType.CUSTOMERS_WITH_DEBT)))
        assertEquals(
            AiProposalRejectReason.INVALID_QUERY_SHAPE,
            rejectedReason(BusinessQueryProposal(BusinessQueryType.CUSTOMERS_WITH_DEBT, customerName = "María")),
        )
        assertEquals(
            AiProposalRejectReason.INVALID_QUERY_SHAPE,
            rejectedReason(
                BusinessQueryProposal(
                    BusinessQueryType.CUSTOMERS_WITH_DEBT,
                    period = AiPeriodCandidate(2026, 8, 1, 2026, 8, 31),
                ),
            ),
        )
    }

    @Test
    fun `sales for period query requires valid period and no customer`() {
        assertTrue(
            accepted(
                BusinessQueryProposal(
                    BusinessQueryType.SALES_FOR_PERIOD,
                    period = AiPeriodCandidate(2026, 8, 1, 2026, 8, 31),
                ),
            ),
        )
        assertEquals(
            AiProposalRejectReason.INVALID_QUERY_SHAPE,
            rejectedReason(BusinessQueryProposal(BusinessQueryType.SALES_FOR_PERIOD)),
        )
        assertEquals(
            AiProposalRejectReason.INVALID_QUERY_SHAPE,
            rejectedReason(
                BusinessQueryProposal(
                    BusinessQueryType.SALES_FOR_PERIOD,
                    customerName = "María",
                    period = AiPeriodCandidate(2026, 8, 1, 2026, 8, 31),
                ),
            ),
        )
        assertEquals(
            AiProposalRejectReason.INVALID_QUERY_SHAPE,
            rejectedReason(
                BusinessQueryProposal(
                    BusinessQueryType.SALES_FOR_PERIOD,
                    period = AiPeriodCandidate(2026, 13, 1, 2026, 8, 31),
                ),
            ),
        )
        assertEquals(
            AiProposalRejectReason.INVALID_QUERY_SHAPE,
            rejectedReason(
                BusinessQueryProposal(
                    BusinessQueryType.SALES_FOR_PERIOD,
                    period = AiPeriodCandidate(2026, 8, 31, 2026, 8, 1),
                ),
            ),
        )
    }

    @Test
    fun `sales for period compares large years lexicographically without overflow`() {
        // start 214748-12-31 < end 214749-01-01 must sort forward and be accepted. The raw Int
        // formula would wrap end (214749 * 10000 exceeds Int.MAX_VALUE) and reject this range.
        assertTrue(
            accepted(
                BusinessQueryProposal(
                    BusinessQueryType.SALES_FOR_PERIOD,
                    period = AiPeriodCandidate(214748, 12, 31, 214749, 1, 1),
                ),
            ),
        )
        // The inverse range must be rejected (start after end).
        assertEquals(
            AiProposalRejectReason.INVALID_QUERY_SHAPE,
            rejectedReason(
                BusinessQueryProposal(
                    BusinessQueryType.SALES_FOR_PERIOD,
                    period = AiPeriodCandidate(214749, 1, 1, 214748, 12, 31),
                ),
            ),
        )
    }

    // ---- RequiresClarificationProposal ----

    @Test
    fun `clarification with enum reason and nonempty slots is accepted`() {
        val proposal = RequiresClarificationProposal(
            ClarificationReason.AMBIGUOUS_CUSTOMER,
            setOf(UnresolvedSlot.CUSTOMER, UnresolvedSlot.AMOUNT),
        )
        val result = SafeAiProposalValidator.validate(proposal)
        assertTrue(result is AiProposalValidationResult.Accepted)
        assertEquals(2, proposal.unresolvedSlots.size)
        assertTrue(proposal.unresolvedSlots.contains(UnresolvedSlot.CUSTOMER))
        assertTrue(proposal.unresolvedSlots.contains(UnresolvedSlot.AMOUNT))
        assertEquals(1, proposal.schemaVersion)
    }

    @Test
    fun `clarification with empty slots is rejected`() {
        assertEquals(
            AiProposalRejectReason.EMPTY_SLOTS,
            rejectedReason(
                RequiresClarificationProposal(ClarificationReason.MISSING_CUSTOMER, emptySet()),
            ),
        )
    }

    @Test
    fun `clarification slots are an immutable canonical set`() {
        val duplicateInput = LinkedHashSet<UnresolvedSlot>()
        duplicateInput.add(UnresolvedSlot.PRODUCT)
        duplicateInput.add(UnresolvedSlot.PRODUCT)
        val proposal = RequiresClarificationProposal(ClarificationReason.AMBIGUOUS_PRODUCT, duplicateInput)

        // Set semantics canonicalize duplicates per contract; the exposed set is unmodifiable.
        assertEquals(setOf(UnresolvedSlot.PRODUCT), proposal.unresolvedSlots)
        assertTrue(accepted(proposal))
        try {
            @Suppress("UNCHECKED_CAST")
            (proposal.unresolvedSlots as MutableSet<UnresolvedSlot>).add(UnresolvedSlot.CUSTOMER)
            throw AssertionError("unresolvedSlots must be unmodifiable")
        } catch (expected: UnsupportedOperationException) {
            // expected
        }
    }

    @Test
    fun `every proposal type carries schema version 1`() {
        assertEquals(1, sale().schemaVersion)
        assertEquals(1, validPayment().schemaVersion)
        assertEquals(1, BusinessQueryProposal(BusinessQueryType.CUSTOMERS_WITH_DEBT).schemaVersion)
        assertEquals(
            1,
            RequiresClarificationProposal(
                ClarificationReason.MISSING_CUSTOMER,
                setOf(UnresolvedSlot.CUSTOMER),
            ).schemaVersion,
        )
    }
}
