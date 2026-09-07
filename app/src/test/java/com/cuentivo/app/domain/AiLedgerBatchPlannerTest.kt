package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.FinancialRejectionReason
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.UncertainLedgerField
import com.cuentivo.app.ai.ledger.UnreadableLedgerField
import com.cuentivo.app.data.local.CustomerEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLedgerBatchPlannerTest {

    private val businessId = "biz-test-123"
    private val userId = "user-test-456"
    private val todayEpochDay = LocalDate.of(2026, 9, 6).toEpochDay()
    private val nowEpochMillis = 1757184000000L

    @Test
    fun plan_rejects_more_than_25_operations() {
        val ops = (1..26).map { i ->
            LedgerOperation(
                lineIndex = i,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Cliente $i",
                amountCents = "1000",
                date = SafeDateCandidate(2026, 9, 1),
            )
        }

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = ops,
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Rejected)
        val rejection = (result as BatchPlanningResult.Rejected).rejections.first()
        assertTrue(rejection is BatchPlanningRejection.ExceedsBatchCapacity)
        assertEquals(25, (rejection as BatchPlanningRejection.ExceedsBatchCapacity).maxAllowed)
        assertEquals(26, rejection.actualCount)
    }

    @Test
    fun plan_deduplicates_repeated_customer_across_rows() {
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "María Gómez",
                amountCents = "1000",
                date = SafeDateCandidate(2026, 9, 1),
            ),
            LedgerOperation(
                lineIndex = 2,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "maria gomez ",
                amountCents = "2000",
                date = SafeDateCandidate(2026, 9, 2),
            ),
            LedgerOperation(
                lineIndex = 3,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "MARIA GOMEZ",
                amountCents = "3000",
                date = SafeDateCandidate(2026, 9, 3),
            ),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = ops,
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Success)
        val plan = (result as BatchPlanningResult.Success).plan
        assertEquals(1, plan.newCustomers.size)
        val createdCustomer = plan.newCustomers.first()
        assertEquals("maria gomez", createdCustomer.normalizedName)
        assertEquals(3, plan.sales.size)
        assertTrue(plan.sales.all { it.customerId == createdCustomer.id })
    }

    @Test
    fun plan_two_payments_for_same_customer_are_applied_correctly() {
        // Customer has initial sale in batch for $50.00 (5000 cents), followed by two payments of $20.00 and $30.00
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Don Beto",
                amountCents = "5000",
                date = SafeDateCandidate(2026, 9, 1),
            ),
            LedgerOperation(
                lineIndex = 2,
                proposedKind = ProposedLedgerKind.PAYMENT,
                customerName = "Don Beto",
                amountCents = "2000",
                date = SafeDateCandidate(2026, 9, 2),
            ),
            LedgerOperation(
                lineIndex = 3,
                proposedKind = ProposedLedgerKind.PAYMENT,
                customerName = "Don Beto",
                amountCents = "3000",
                date = SafeDateCandidate(2026, 9, 3),
            ),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = ops,
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Success)
        val plan = (result as BatchPlanningResult.Success).plan
        assertEquals(1, plan.sales.size)
        assertEquals(2, plan.payments.size)
        assertEquals(2, plan.paymentAllocations.size)
        val saleId = plan.sales.first().id
        assertEquals(saleId, plan.paymentAllocations[0].saleId)
        assertEquals(2000L, plan.paymentAllocations[0].amountCents)
        assertEquals(saleId, plan.paymentAllocations[1].saleId)
        assertEquals(3000L, plan.paymentAllocations[1].amountCents)
    }

    @Test
    fun plan_rejects_overpayment_exceeding_debt() {
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Don Beto",
                amountCents = "5000",
                date = SafeDateCandidate(2026, 9, 1),
            ),
            LedgerOperation(
                lineIndex = 2,
                proposedKind = ProposedLedgerKind.PAYMENT,
                customerName = "Don Beto",
                amountCents = "6000", // Exceeds 5000
                date = SafeDateCandidate(2026, 9, 2),
            ),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = ops,
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Rejected)
        val rejection = (result as BatchPlanningResult.Rejected).rejections.first()
        assertTrue(rejection is BatchPlanningRejection.FinancialError)
        assertEquals(FinancialRejectionReason.PAYMENT_EXCEEDS_DEBT, (rejection as BatchPlanningRejection.FinancialError).reason)
        assertEquals(2, rejection.lineIndex)
    }

    @Test
    fun plan_rejects_payment_without_debt() {
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.PAYMENT,
                customerName = "Cliente Sin Deuda",
                amountCents = "1000",
                date = SafeDateCandidate(2026, 9, 1),
            ),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = ops,
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Rejected)
        val rejection = (result as BatchPlanningResult.Rejected).rejections.first()
        assertTrue(rejection is BatchPlanningRejection.FinancialError)
        assertEquals(FinancialRejectionReason.PAYMENT_WITHOUT_DEBT, (rejection as BatchPlanningRejection.FinancialError).reason)
    }

    @Test
    fun plan_uses_page_date_when_row_date_is_missing() {
        val pageDate = LocalDate.of(2026, 8, 15).toEpochDay()
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Ana",
                amountCents = "1500",
                date = null, // Missing row date
            ),
            LedgerOperation(
                lineIndex = 2,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Beto",
                amountCents = "2500",
                date = SafeDateCandidate(2026, 8, 10), // Specific row date
            ),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = ops,
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = pageDate,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Success)
        val plan = (result as BatchPlanningResult.Success).plan
        assertEquals(pageDate, plan.sales[0].saleDateEpochDay)
        assertEquals(LocalDate.of(2026, 8, 10).toEpochDay(), plan.sales[1].saleDateEpochDay)
    }

    @Test
    fun plan_rejects_when_row_date_is_missing_and_no_page_date_provided() {
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Ana",
                amountCents = "1500",
                date = null,
            ),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = ops,
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Rejected)
        val rejection = (result as BatchPlanningResult.Rejected).rejections.first()
        assertTrue(rejection is BatchPlanningRejection.MissingDate)
        assertEquals(1, (rejection as BatchPlanningRejection.MissingDate).lineIndex)
    }

    @Test
    fun plan_skips_unreadable_or_uncertain_rows_and_reports_skipped_indices() {
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Valida",
                amountCents = "1000",
                date = SafeDateCandidate(2026, 9, 1),
            ),
            LedgerOperation(
                lineIndex = 2,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Con Duda",
                amountCents = "2000",
                date = SafeDateCandidate(2026, 9, 1),
                uncertainFields = listOf(UncertainLedgerField.AMOUNT),
            ),
            LedgerOperation(
                lineIndex = 3,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Ilegible",
                amountCents = "3000",
                date = SafeDateCandidate(2026, 9, 1),
                unreadableFields = listOf(UnreadableLedgerField.CUSTOMER),
            ),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = ops,
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Success)
        val plan = (result as BatchPlanningResult.Success).plan
        assertEquals(1, plan.executableOperationsCount)
        assertEquals(listOf(2, 3), plan.skippedRowIndices)
        assertEquals(1, plan.sales.size)
        assertEquals("valida", plan.sales.first().sellerUserId.let { plan.newCustomers.first().normalizedName })
    }

    @Test
    fun plan_idempotency_key_is_deterministic() {
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Carlos",
                amountCents = "1000",
                date = SafeDateCandidate(2026, 9, 1),
            ),
        )

        val result1 = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = ops,
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        val result2 = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = ops,
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result1 is BatchPlanningResult.Success)
        assertTrue(result2 is BatchPlanningResult.Success)
        assertEquals(
            (result1 as BatchPlanningResult.Success).plan.batchIdempotencyKey,
            (result2 as BatchPlanningResult.Success).plan.batchIdempotencyKey,
        )
    }

    @Test
    fun resolveDate_absent_date_takes_page_date_and_invalid_date_does_not_take_page_date() {
        val pageDate = LocalDate.of(2026, 8, 1).toEpochDay()

        // 1. Absent date (null candidate) takes page date
        assertEquals(pageDate, AiLedgerBatchPlanner.resolveDate(null, pageDate, todayEpochDay))

        // 2. Invalid date candidate (Feb 31) does NOT take page date
        val invalidCandidate = SafeDateCandidate(2026, 2, 31)
        org.junit.Assert.assertNull(AiLedgerBatchPlanner.resolveDate(invalidCandidate, pageDate, todayEpochDay))

        // 3. Future date candidate does NOT take page date
        val futureCandidate = SafeDateCandidate(2026, 12, 31)
        org.junit.Assert.assertNull(AiLedgerBatchPlanner.resolveDate(futureCandidate, pageDate, todayEpochDay))

        // 4. Valid date candidate uses its own date
        val validCandidate = SafeDateCandidate(2026, 8, 10)
        assertEquals(LocalDate.of(2026, 8, 10).toEpochDay(), AiLedgerBatchPlanner.resolveDate(validCandidate, pageDate, todayEpochDay))
    }

    @Test
    fun plan_rejects_invalid_date_candidate_even_when_page_date_is_provided() {
        val pageDate = LocalDate.of(2026, 8, 15).toEpochDay()
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Ana",
                amountCents = "1500",
                date = SafeDateCandidate(2026, 2, 31), // Invalid calendar date
            ),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = ops,
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = pageDate,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Rejected)
        val rejection = (result as BatchPlanningResult.Rejected).rejections.first()
        assertTrue(rejection is BatchPlanningRejection.MissingDate)
        assertEquals(1, (rejection as BatchPlanningRejection.MissingDate).lineIndex)
    }
}
