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
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
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
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
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
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
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
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
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
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
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
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
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
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
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
    fun plan_rejects_the_page_when_any_row_is_unresolved() {
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
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue("debe rechazar la página entera, no saltar los renglones", result is BatchPlanningResult.Rejected)
        val rejection = (result as BatchPlanningResult.Rejected).rejections.single()
        assertTrue(rejection is BatchPlanningRejection.UnresolvedRows)
        assertEquals(
            "el rechazo tiene que nombrar los renglones que faltan",
            listOf(2, 3),
            (rejection as BatchPlanningRejection.UnresolvedRows).lineIndices,
        )
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
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        val result2 = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
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
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
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

    // Aquí vivía `unaccepted_automatic_candidate_does_not_change_operation_nor_batch_key`, que
    // comparaba `batchKeyFor(businessId, "session-test")` consigo mismo. Tras la ronda 3 eso es una
    // constante comparada con otra constante: la clave es función de (negocio, sesión) y no puede ver
    // el ancla, ni el importe, ni el cliente, así que la aserción no podía fallar nunca. Se retira en
    // vez de conservar un verde que no prueba nada. Lo que sí puede fallar es la idempotencia del
    // reintento, y eso vive en `LedgerSessionKeyIdempotencyTest`. El ancla aceptada se prueba justo
    // debajo, contra el plan real.

    @Test
    fun accepting_customer_anchor_writes_sale_to_that_customerId_not_derived_from_name() {
        val existingCustomer = CustomerEntity(
            id = "customer-uuid-999",
            businessId = businessId,
            name = "Mayra T5",
            normalizedName = "mayra t5",
            phone = null,
            email = null,
            address = null,
            notes = null,
            creditLimitCents = 0L,
            isActive = true,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            deletedAtEpochMillis = null,
        )

        val opAnchored = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Mayra T5 Raw Paper Text", // Raw paper text preserved!
            amountCents = "3500",
            date = SafeDateCandidate(2026, 9, 1),
            customerId = existingCustomer.id, // Explicit human acceptance
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = "session-test",
            operations = listOf(opAnchored),
            blockedLineIndices = emptySet(),
            existingCustomerMap = mapOf(existingCustomer.normalizedName to existingCustomer),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Success)
        val plan = (result as BatchPlanningResult.Success).plan

        // Must write sale under customer-uuid-999 and NOT create a new customer entity
        assertEquals(0, plan.newCustomers.size)
        assertEquals(1, plan.sales.size)
        assertEquals(existingCustomer.id, plan.sales.first().customerId)
    }

    @Test
    fun anchored_payment_is_assigned_only_to_anchored_customer_sales() {
        val custA = CustomerEntity(
            id = "cust-A-111",
            businessId = businessId,
            name = "Rosa",
            normalizedName = "rosa",
            phone = null, email = null, address = null, notes = null,
            creditLimitCents = 0L, isActive = true,
            createdAtEpochMillis = nowEpochMillis, updatedAtEpochMillis = nowEpochMillis, deletedAtEpochMillis = null,
        )
        val custB = CustomerEntity(
            id = "cust-B-222",
            businessId = businessId,
            name = "Rosa Maria",
            normalizedName = "rosa maria",
            phone = null, email = null, address = null, notes = null,
            creditLimitCents = 0L, isActive = true,
            createdAtEpochMillis = nowEpochMillis, updatedAtEpochMillis = nowEpochMillis, deletedAtEpochMillis = null,
        )

        val ops = listOf(
            // Sale for Cust A
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Rosa",
                amountCents = "5000",
                date = SafeDateCandidate(2026, 9, 1),
                customerId = custA.id,
            ),
            // Payment anchored specifically to Cust A
            LedgerOperation(
                lineIndex = 2,
                proposedKind = ProposedLedgerKind.PAYMENT,
                customerName = "Rosa",
                amountCents = "2000",
                date = SafeDateCandidate(2026, 9, 2),
                customerId = custA.id,
            ),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
            existingCustomerMap = mapOf(custA.normalizedName to custA, custB.normalizedName to custB),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Success)
        val plan = (result as BatchPlanningResult.Success).plan

        assertEquals(custA.id, plan.payments.first().customerId)
        assertEquals(custA.id, plan.paymentMovements.first().customerId)
    }

    @Test
    fun two_operations_anchored_to_different_customers_do_not_merge_even_with_same_name() {
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Rosa",
                amountCents = "1000",
                date = SafeDateCandidate(2026, 9, 1),
                customerId = "cust-uuid-1",
            ),
            LedgerOperation(
                lineIndex = 2,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Rosa",
                amountCents = "2000",
                date = SafeDateCandidate(2026, 9, 1),
                customerId = "cust-uuid-2",
            ),
        )

        // Both must exist: an anchor names somebody the person picked from a real list, and one
        // that no longer exists is refused rather than minted.
        val uno = customer("cust-uuid-1", "Rosa", "rosa")
        val dos = customer("cust-uuid-2", "Rosa Perez", "rosa perez")

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = "session-test",
            operations = ops,
            blockedLineIndices = emptySet(),
            existingCustomerMap = mapOf(uno.normalizedName to uno, dos.normalizedName to dos),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Success)
        val plan = (result as BatchPlanningResult.Success).plan

        // The paper said "Rosa" on both rows. Without the anchors they would have merged into one
        // customer and one running balance; with them, the money follows what the person picked.
        assertEquals(2, plan.sales.size)
        assertEquals("cust-uuid-1", plan.sales[0].customerId)
        assertEquals("cust-uuid-2", plan.sales[1].customerId)
        assertTrue("no debe crear clientes nuevos", plan.newCustomers.isEmpty())
    }

    private fun customer(id: String, name: String, normalized: String) = CustomerEntity(
        id = id,
        businessId = businessId,
        name = name,
        normalizedName = normalized,
        phone = null,
        email = null,
        address = null,
        notes = null,
        creditLimitCents = 0L,
        isActive = true,
        createdAtEpochMillis = nowEpochMillis,
        updatedAtEpochMillis = nowEpochMillis,
        deletedAtEpochMillis = null,
    )

    @Test
    fun plan_rejects_an_anchor_to_a_customer_that_no_longer_exists() {
        // The page was reviewed, the person picked a customer from the list they were shown, and by
        // the time they confirmed it was archived or deleted. Planning it anyway would build a
        // CustomerEntity carrying that id, and insertCustomer aborts on conflict, so the whole
        // transaction would blow up as an unexplained "could not register the notebook".
        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = "session-test",
            operations = listOf(
                LedgerOperation(
                    lineIndex = 0,
                    proposedKind = ProposedLedgerKind.SALE,
                    customerName = "Rosa",
                    amountCents = "25000",
                    date = SafeDateCandidate(2026, 9, 1),
                    customerId = "cliente-que-ya-no-esta",
                ),
            ),
            blockedLineIndices = emptySet(),
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Rejected)
        val rejection = (result as BatchPlanningResult.Rejected).rejections.first()
        assertTrue(rejection is BatchPlanningRejection.UnknownCustomerAnchor)
        assertEquals("cliente-que-ya-no-esta", (rejection as BatchPlanningRejection.UnknownCustomerAnchor).customerId)
    }

    @Test
    fun an_anchor_never_mints_a_customer_carrying_its_own_id() {
        // The only customers this planner creates are the ones the notebook named and nobody
        // matched. If an anchor could mint one, it would be creating a row with an id chosen from a
        // list somewhere else, which is how you end up with two customers sharing an identity.
        val existing = CustomerEntity(
            id = "cust-real",
            businessId = businessId,
            name = "Rosa",
            normalizedName = CustomerNameNormalizer.key("Rosa"),
            phone = null,
            email = null,
            address = null,
            notes = null,
            creditLimitCents = 0L,
            isActive = true,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            deletedAtEpochMillis = null,
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = "session-test",
            operations = listOf(
                LedgerOperation(
                    lineIndex = 0,
                    proposedKind = ProposedLedgerKind.SALE,
                    customerName = "Rossa",
                    amountCents = "25000",
                    date = SafeDateCandidate(2026, 9, 1),
                    customerId = "cust-real",
                ),
            ),
            blockedLineIndices = emptySet(),
            existingCustomerMap = mapOf(existing.normalizedName to existing),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Success)
        val plan = (result as BatchPlanningResult.Success).plan
        // The name on the paper said "Rossa" and the anchor said this is Rosa: no new customer, and
        // the sale goes to the customer the person picked.
        assertTrue("no debe crear ningun cliente", plan.newCustomers.isEmpty())
        assertEquals("cust-real", plan.sales.single().customerId)
    }

    @Test
    fun prior_debt_carries_the_persisted_marker_and_a_normal_sale_does_not() {
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.PRIOR_DEBT,
                customerName = "Rosa Martinez",
                amountCents = "15000",
                description = "Saldo anterior",
                date = SafeDateCandidate(2026, 9, 1),
            ),
            LedgerOperation(
                lineIndex = 2,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Rosa Martinez",
                amountCents = "2500",
                description = "Cuaderno",
                date = SafeDateCandidate(2026, 9, 2),
            ),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = "session-prior-debt-marker",
            operations = ops,
            blockedLineIndices = emptySet(),
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue(result is BatchPlanningResult.Success)
        val plan = (result as BatchPlanningResult.Success).plan
        assertEquals(2, plan.sales.size)
        // The prior debt is labelled exactly like the one `OpeningBalance` writes when a customer is
        // created, so the readers that already exist — the history, `getRegisteredOperationsInRange`,
        // `salesTotalCents` — can tell it apart from a real sale. A normal sale keeps no marker.
        val priorDebtSale = plan.sales.first { it.saleNumber == "SA-$nowEpochMillis-1" }
        assertEquals(OpeningBalance.NOTES_MARKER, priorDebtSale.notes)
        val normalSale = plan.sales.first { it.saleNumber == "V-$nowEpochMillis-2" }
        assertEquals(null, normalSale.notes)
        // The charge movement carries the same marker, because the builder copies `notes` onto it.
        assertEquals(
            OpeningBalance.NOTES_MARKER,
            plan.chargeMovements.first { it.saleId == priorDebtSale.id }.notes,
        )
        assertEquals(null, plan.chargeMovements.first { it.saleId == normalSale.id }.notes)
    }
}
