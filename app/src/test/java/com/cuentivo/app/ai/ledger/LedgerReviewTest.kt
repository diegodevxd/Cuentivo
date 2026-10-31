package com.cuentivo.app.ai.ledger

import com.cuentivo.app.domain.CustomerNameNormalizer
import com.cuentivo.app.domain.AiLedgerBatchPlanner
import com.cuentivo.app.ui.ai.AiDebugLabMachine
import com.cuentivo.app.ui.ai.AiDebugLabModel
import com.cuentivo.app.ui.ai.AiDebugLabState
import com.cuentivo.app.ui.ai.AiLedgerConfirmOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LedgerReviewTest {

    // =========================================================================
    // SHARED FIXTURES: Parity between TypeScript and Kotlin
    // =========================================================================
    @Test
    fun shared_fixtures_parity_with_contract_ts() {
        val fixturesFile = File("../shared-fixtures/ledgerOperationFixtures.json")
        val resolvedFile = if (fixturesFile.exists()) fixturesFile else File("shared-fixtures/ledgerOperationFixtures.json")
        assertTrue("ledgerOperationFixtures.json must exist", resolvedFile.exists())

        val jsonText = resolvedFile.readText()
        val fixtures = parseSharedFixtures(jsonText)
        assertEquals(26, fixtures.size)

        for (fixture in fixtures) {
            val actual = isRegisterableLedgerOperation(fixture.op)
            assertEquals("Fixture failed: ${fixture.name}", fixture.expected, actual)
        }
    }

    private data class ParsedFixture(
        val name: String,
        val op: LedgerOperation,
        val expected: Boolean,
    )

    /**
     * Standard JSON parser using org.json.JSONArray to ensure exact parity with contract.ts.
     */
    private fun parseSharedFixtures(json: String): List<ParsedFixture> {
        val array = org.json.JSONArray(json)
        val result = mutableListOf<ParsedFixture>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.getString("name")
            val expected = obj.getBoolean("expected")
            val opObj = obj.getJSONObject("op")

            val lineIndex = opObj.getInt("lineIndex")
            val proposedKind = if (opObj.isNull("proposedKind") || !opObj.has("proposedKind")) {
                null
            } else {
                ProposedLedgerKind.valueOf(opObj.getString("proposedKind"))
            }
            val customerName = if (opObj.isNull("customerName") || !opObj.has("customerName")) {
                null
            } else {
                opObj.getString("customerName")
            }
            val amountCents = if (opObj.isNull("amountCents") || !opObj.has("amountCents")) {
                null
            } else {
                opObj.getString("amountCents")
            }

            val uncertainList = mutableListOf<UncertainLedgerField>()
            val uncertArr = opObj.getJSONArray("uncertainFields")
            for (u in 0 until uncertArr.length()) {
                uncertainList.add(UncertainLedgerField.valueOf(uncertArr.getString(u)))
            }

            val unreadList = mutableListOf<UnreadableLedgerField>()
            val unreadArr = opObj.getJSONArray("unreadableFields")
            for (r in 0 until unreadArr.length()) {
                unreadList.add(UnreadableLedgerField.valueOf(unreadArr.getString(r)))
            }

            val op = LedgerOperation(
                lineIndex = lineIndex,
                proposedKind = proposedKind,
                customerName = customerName,
                amountCents = amountCents,
                uncertainFields = uncertainList,
                unreadableFields = unreadList,
            )

            result.add(ParsedFixture(name, op, expected))
        }

        return result
    }

    // =========================================================================
    // DEFECT 1: Editing preserves known customer balance
    // =========================================================================
    @Test
    fun defect1_editing_operation_preserves_known_customer_balance_without_reinyecting() {
        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        val op = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = "1000",
        )
        val juanKey = CustomerNameNormalizer.key("Juan")
        val balances = mapOf(juanKey to 5000L)

        val reviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(op),
            existingCustomerBalances = balances,
            generation = extracting.generation,
        )

        val reviewState = reviewing.state as AiDebugLabState.LedgerReviewing
        assertEquals(5000L, reviewState.review.customerGroups.first().previousBalanceCents)
        assertEquals(6000L, reviewState.review.customerGroups.first().resultingBalanceCents)
        assertFalse(reviewState.review.customerGroups.first().isNew)

        // Now edit the operation WITHOUT providing existingCustomerBalances again
        val updatedModel = AiDebugLabMachine.onUpdateLedgerOperations(reviewing, "sess-1") { ops ->
            LedgerReviewEngine.setOperationAmount(ops, 1, "2000")
        }

        val updatedState = updatedModel.state as AiDebugLabState.LedgerReviewing
        val group = updatedState.review.customerGroups.first()
        assertFalse("Customer must not turn into a new customer on edit", group.isNew)
        assertEquals("Previous balance must be preserved across edits", 5000L, group.previousBalanceCents)
        assertEquals("Resulting balance must update with preserved previous balance", 7000L, group.resultingBalanceCents)
    }

    // =========================================================================
    // DEFECT 2: Arithmetic overflow produces blocking diagnostic, not fake balance
    // =========================================================================
    @Test
    fun defect2_overflow_produces_blocking_diagnostic_and_is_not_executed() {
        val nearMax = Long.MAX_VALUE - 100L
        val balances = mapOf(CustomerNameNormalizer.key("Carlos") to nearMax)

        val op1 = LedgerOperation(1, ProposedLedgerKind.SALE, "Carlos", "50")
        val op2 = LedgerOperation(2, ProposedLedgerKind.SALE, "Carlos", "100") // Will overflow nearMax + 50 + 100

        val review = LedgerReviewEngine.build(listOf(op1, op2), existingCustomerBalances = balances)
        val group = review.customerGroups.first()

        assertEquals(1, group.executableOperations.size)
        assertEquals(1, group.financialDiagnostics.size)
        assertEquals(FinancialRejectionReason.OVERFLOW, group.financialDiagnostics.first().reason)
        assertEquals(2, group.financialDiagnostics.first().lineIndex)

        assertTrue(review.summary.hasFinancialErrors)
        assertEquals(1, review.summary.financialDiagnostics.size)
        assertEquals(FinancialRejectionReason.OVERFLOW, review.summary.financialDiagnostics.first().reason)
        // Syntactically registerable
        assertEquals(2, review.summary.registerableOperationsCount)
        // Only 1 financially executable
        assertEquals(1, review.summary.executableOperationsCount)
        // Resulting balance retains the last valid state before overflow, not an erroneous wrap-around
        assertEquals(nearMax + 50L, group.resultingBalanceCents)
    }

    // =========================================================================
    // DEFECT 3: Financial executability: payment without debt & zero amount rejected
    // =========================================================================
    @Test
    fun defect3_payment_without_debt_is_blocked_with_financial_diagnostic() {
        // Correcting the old flawed test: payment of 3000 without prior debt must NOT leave resultingBalance in -3000
        val paymentOp = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.PAYMENT,
            customerName = "Maria",
            amountCents = "3000",
        )
        // Syntactically registerable in contract v3
        assertTrue(isRegisterableLedgerOperation(paymentOp))

        val review = LedgerReviewEngine.build(listOf(paymentOp))
        val group = review.customerGroups.first()

        assertEquals(0, group.executableOperations.size)
        assertEquals(1, group.financialDiagnostics.size)
        assertEquals(FinancialRejectionReason.PAYMENT_WITHOUT_DEBT, group.financialDiagnostics.first().reason)
        assertEquals(0L, group.resultingBalanceCents) // Not -3000L !

        assertEquals(1, review.summary.registerableOperationsCount)
        assertEquals(0, review.summary.executableOperationsCount)
        assertTrue(review.summary.hasFinancialErrors)
    }

    @Test
    fun defect3_payment_exceeding_debt_is_blocked_with_financial_diagnostic() {
        val balances = mapOf(CustomerNameNormalizer.key("Maria") to 2000L)
        val paymentOp = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.PAYMENT,
            customerName = "Maria",
            amountCents = "3000",
        )
        val review = LedgerReviewEngine.build(listOf(paymentOp), existingCustomerBalances = balances)
        val group = review.customerGroups.first()

        assertEquals(0, group.executableOperations.size)
        assertEquals(1, group.financialDiagnostics.size)
        assertEquals(FinancialRejectionReason.PAYMENT_EXCEEDS_DEBT, group.financialDiagnostics.first().reason)
        assertEquals(2000L, group.resultingBalanceCents) // Balance unchanged
        assertEquals(0, review.summary.executableOperationsCount)
    }

    @Test
    fun defect3_zero_amount_is_blocked_financially() {
        val zeroOp = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = "0",
        )
        // Syntactically registerable in contract v3
        assertTrue(isRegisterableLedgerOperation(zeroOp))

        val review = LedgerReviewEngine.build(listOf(zeroOp))
        val group = review.customerGroups.first()

        assertEquals(0, group.executableOperations.size)
        assertEquals(1, group.financialDiagnostics.size)
        assertEquals(FinancialRejectionReason.ZERO_AMOUNT, group.financialDiagnostics.first().reason)
        assertEquals(1, review.summary.registerableOperationsCount)
        assertEquals(0, review.summary.executableOperationsCount)
    }

    // =========================================================================
    // DEFECT 4: Summary newCustomersCount only counts customers actually created
    // =========================================================================
    @Test
    fun defect4_newCustomersCount_distinguishes_visible_groups_from_created_customers() {
        // Unreadable amount for new customer Juan: visible group = 1, but newCustomersCount = 0
        val unreadableOp = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = null,
            unreadableFields = listOf(UnreadableLedgerField.AMOUNT),
        )
        val review = LedgerReviewEngine.build(listOf(unreadableOp))

        assertEquals(1, review.summary.visibleCustomersCount)
        assertEquals(1, review.summary.totalCustomersCount) // Compatibility alias
        assertEquals("New customers count must be 0 when no rows are executable", 0, review.summary.newCustomersCount)
        assertEquals(0, review.summary.executableOperationsCount)
        assertEquals(1, review.summary.unregisterableOperationsCount)

        // Once completed with valid amount, newCustomersCount becomes 1
        val completedOp = unreadableOp.copy(amountCents = "1500", unreadableFields = emptyList())
        val completedReview = LedgerReviewEngine.build(listOf(completedOp))
        assertEquals(1, completedReview.summary.newCustomersCount)
        assertEquals(1, completedReview.summary.executableOperationsCount)
    }

    // =========================================================================
    // DEFECT 5: Review snapshot immutability
    // =========================================================================
    @Test
    fun defect5_snapshot_immutability_isolates_against_external_list_mutation() {
        val mutableUncertain = mutableListOf(UncertainLedgerField.KIND)
        val mutableUnreadable = mutableListOf(UnreadableLedgerField.AMOUNT)
        val op = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = "1000",
            uncertainFields = mutableUncertain,
            unreadableFields = mutableUnreadable,
        )
        val mutableOps = mutableListOf(op)
        val mutableDiscards = mutableListOf(
            LedgerDiscardDiagnostic(0, 99, LedgerDiscardReason.MALFORMED)
        )

        val review = LedgerReviewEngine.build(mutableOps, discards = mutableDiscards)

        // Mutate caller lists
        mutableOps.clear()
        mutableDiscards.clear()
        mutableUncertain.clear()
        mutableUnreadable.clear()

        // Review must remain completely untouched
        assertEquals(1, review.operations.size)
        assertEquals(1, review.discards.size)
        assertEquals(1, review.operations.first().uncertainFields.size)
        assertEquals(1, review.operations.first().unreadableFields.size)
        assertEquals(1, review.customerGroups.first().operations.size)
    }

    // =========================================================================
    // OTHER FLOW & MACHINE TESTS
    // =========================================================================
    @Test
    fun unreadable_operation_is_not_registerable_and_completing_it_makes_it_registerable() {
        val unreadableOp = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Carlos",
            amountCents = null,
            unreadableFields = listOf(UnreadableLedgerField.AMOUNT),
        )
        assertFalse(isRegisterableLedgerOperation(unreadableOp))

        val initialReview = LedgerReviewEngine.build(listOf(unreadableOp))
        assertEquals(0, initialReview.summary.registerableOperationsCount)
        assertEquals(1, initialReview.summary.unregisterableOperationsCount)
        assertEquals(0, initialReview.summary.salesCount)

        // Complete the unreadable amount
        val updatedOps = LedgerReviewEngine.setOperationAmount(listOf(unreadableOp), 1, "5000")
        val completedOp = updatedOps.first()
        assertTrue(isRegisterableLedgerOperation(completedOp))
        assertEquals("5000", completedOp.amountCents)
        assertTrue(completedOp.unreadableFields.isEmpty())

        val updatedReview = LedgerReviewEngine.build(updatedOps)
        assertEquals(1, updatedReview.summary.registerableOperationsCount)
        assertEquals(0, updatedReview.summary.unregisterableOperationsCount)
        assertEquals(1, updatedReview.summary.salesCount)
        assertEquals(1, updatedReview.customerGroups.size)
        assertEquals(5000L, updatedReview.customerGroups.first().resultingBalanceCents)
    }

    @Test
    fun confidence_does_not_affect_isRegisterableLedgerOperation_boundary_rules() {
        // Rule: isRegisterableLedgerOperation never reads confidence.
        // Boundary case 1: low confidence (e.g. 0.10) with empty uncertain/unreadable lists IS registerable.
        val lowConfidenceRegisterableOp = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "3800",
            uncertainFields = emptyList(),
            unreadableFields = emptyList(),
        )
        assertTrue(isRegisterableLedgerOperation(lowConfidenceRegisterableOp))

        // Boundary case 2: high confidence (e.g. 0.99) with unreadableFields non-empty is NOT registerable.
        val highConfidenceUnreadableOp = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "3800",
            unreadableFields = listOf(UnreadableLedgerField.AMOUNT),
        )
        assertFalse(isRegisterableLedgerOperation(highConfidenceUnreadableOp))
    }

    @Test
    fun changing_operation_kind_updates_customer_group_and_resulting_debt() {
        val op = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Pedro",
            amountCents = "10000",
        )
        val balances = mapOf(CustomerNameNormalizer.key("Pedro") to 20000L)
        val initialReview = LedgerReviewEngine.build(listOf(op), existingCustomerBalances = balances)

        assertEquals(1, initialReview.summary.salesCount)
        assertEquals(0, initialReview.summary.paymentsCount)
        assertEquals(0, initialReview.summary.priorDebtsCount)
        assertEquals(20000L, initialReview.customerGroups.first().previousBalanceCents)
        assertEquals(30000L, initialReview.customerGroups.first().resultingBalanceCents)

        // Change kind to PAYMENT
        val paymentOps = LedgerReviewEngine.setOperationKind(listOf(op), 1, ProposedLedgerKind.PAYMENT)
        val paymentReview = LedgerReviewEngine.build(paymentOps, existingCustomerBalances = balances)
        assertEquals(0, paymentReview.summary.salesCount)
        assertEquals(1, paymentReview.summary.paymentsCount)
        assertEquals(0, paymentReview.summary.priorDebtsCount)
        assertEquals(20000L, paymentReview.customerGroups.first().previousBalanceCents)
        assertEquals(10000L, paymentReview.customerGroups.first().resultingBalanceCents)

        // Change kind to PRIOR_DEBT
        val priorDebtOps = LedgerReviewEngine.setOperationKind(paymentOps, 1, ProposedLedgerKind.PRIOR_DEBT)
        val priorDebtReview = LedgerReviewEngine.build(priorDebtOps, existingCustomerBalances = balances)
        assertEquals(0, priorDebtReview.summary.salesCount)
        assertEquals(0, priorDebtReview.summary.paymentsCount)
        assertEquals(1, priorDebtReview.summary.priorDebtsCount)
        assertEquals(20000L, priorDebtReview.customerGroups.first().previousBalanceCents)
        assertEquals(30000L, priorDebtReview.customerGroups.first().resultingBalanceCents)
    }

    @Test
    fun two_operations_for_same_customer_produce_single_customer_group() {
        val op1 = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Ana Gómez",
            amountCents = "1500",
        )
        val op2 = LedgerOperation(
            lineIndex = 2,
            proposedKind = ProposedLedgerKind.PAYMENT,
            customerName = " ana gómez  ",
            amountCents = "500",
        )
        val review = LedgerReviewEngine.build(listOf(op1, op2))

        assertEquals(1, review.customerGroups.size)
        assertEquals(1, review.summary.totalCustomersCount)
        assertEquals(1, review.summary.newCustomersCount)
        assertEquals(2, review.summary.registerableOperationsCount)
        assertEquals(2, review.summary.executableOperationsCount)
        assertEquals(1, review.summary.salesCount)
        assertEquals(1, review.summary.paymentsCount)

        val group = review.customerGroups.first()
        assertEquals("Ana Gómez", group.customerName)
        assertEquals(CustomerNameNormalizer.key("Ana Gómez"), group.normalizedCustomerName)
        assertTrue(group.isNew)
        assertEquals(0L, group.previousBalanceCents)
        assertEquals(1000L, group.resultingBalanceCents)
        assertEquals(2, group.operations.size)
    }

    @Test
    fun existing_customer_is_not_marked_as_new_and_retains_known_balance() {
        val op = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Don Beto",
            amountCents = "4000",
        )
        val balances = mapOf(CustomerNameNormalizer.key("Don Beto") to 15000L)
        val review = LedgerReviewEngine.build(listOf(op), existingCustomerBalances = balances)

        assertEquals(1, review.customerGroups.size)
        assertEquals(1, review.summary.totalCustomersCount)
        assertEquals(0, review.summary.newCustomersCount)

        val group = review.customerGroups.first()
        assertFalse(group.isNew)
        assertEquals(15000L, group.previousBalanceCents)
        assertEquals(19000L, group.resultingBalanceCents)
    }

    @Test
    fun discard_count_matches_discards_diagnostics() {
        val discards = listOf(
            LedgerDiscardDiagnostic(inputIndex = 0, lineIndex = 1, reason = LedgerDiscardReason.MALFORMED),
            LedgerDiscardDiagnostic(inputIndex = 1, lineIndex = null, reason = LedgerDiscardReason.DISALLOWED_PROPERTIES),
        )
        val review = LedgerReviewEngine.build(emptyList(), discards = discards)

        assertEquals(2, review.summary.excludedRowsCount)
        assertEquals(discards, review.discards)
    }

    @Test
    fun remove_operation_updates_summary_and_customer_groups() {
        val op1 = LedgerOperation(lineIndex = 1, proposedKind = ProposedLedgerKind.SALE, customerName = "A", amountCents = "100")
        val op2 = LedgerOperation(lineIndex = 2, proposedKind = ProposedLedgerKind.SALE, customerName = "B", amountCents = "200")

        val initialReview = LedgerReviewEngine.build(listOf(op1, op2))
        assertEquals(2, initialReview.summary.totalCustomersCount)
        assertEquals(2, initialReview.summary.salesCount)

        val updatedOps = LedgerReviewEngine.removeOperation(listOf(op1, op2), 1)
        val updatedReview = LedgerReviewEngine.build(updatedOps)
        assertEquals(1, updatedReview.summary.totalCustomersCount)
        assertEquals(1, updatedReview.summary.salesCount)
        assertEquals("B", updatedReview.customerGroups.first().customerName)
    }

    @Test
    fun machine_stale_extraction_result_is_ignored() {
        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        assertTrue(extracting.state is AiDebugLabState.LedgerExtracting)
        val gen = extracting.generation

        // Result with stale generation
        val stale = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(LedgerOperation(1, ProposedLedgerKind.SALE, "Juan", "100")),
            generation = gen - 1,
        )
        assertSame(extracting, stale)

        // Result with mismatched session ID
        val mismatchedSess = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "other-sess",
            businessId = "biz-1",
            operations = listOf(LedgerOperation(1, ProposedLedgerKind.SALE, "Juan", "100")),
            generation = gen,
        )
        assertSame(extracting, mismatchedSess)

        // Result with mismatched business ID
        val mismatchedBiz = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "other-biz",
            operations = listOf(LedgerOperation(1, ProposedLedgerKind.SALE, "Juan", "100")),
            generation = gen,
        )
        assertSame(extracting, mismatchedBiz)
    }

    @Test
    fun machine_cancel_during_extraction_invalidates_in_flight_result() {
        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        val oldGen = extracting.generation

        val cancelled = AiDebugLabMachine.onCancelLedger(extracting)
        assertEquals(AiDebugLabState.TextEditing, cancelled.state)
        assertTrue(cancelled.generation > oldGen)

        // In-flight result arrives late
        val lateResult = AiDebugLabMachine.onLedgerExtractionResult(
            model = cancelled,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(LedgerOperation(1, ProposedLedgerKind.SALE, "Juan", "100")),
            generation = oldGen,
        )
        assertSame(cancelled, lateResult)
        assertEquals(AiDebugLabState.TextEditing, lateResult.state)
    }

    @Test
    fun machine_voice_is_disabled_during_ledger_states() {
        val initial = AiDebugLabModel()
        assertFalse(initial.isVoiceDisabled)

        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        assertTrue(extracting.isVoiceDisabled)

        val reviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(LedgerOperation(1, ProposedLedgerKind.SALE, "Juan", "100", date = SafeDateCandidate(2026, 9, 1))),
            generation = extracting.generation,
        )
        assertTrue(reviewing.isVoiceDisabled)

        val confirmOutcome = AiDebugLabMachine.onConfirmLedger(reviewing, "sess-1", "biz-1")
        assertTrue(confirmOutcome is AiLedgerConfirmOutcome.Started)
        val confirmingModel = (confirmOutcome as AiLedgerConfirmOutcome.Started).model
        assertTrue(confirmingModel.isVoiceDisabled)

        val afterCancel = AiDebugLabMachine.onCancelLedger(confirmingModel)
        assertEquals(AiDebugLabState.TextEditing, afterCancel.state)
        assertFalse(afterCancel.isVoiceDisabled)
    }

    @Test
    fun machine_update_operations_in_reviewing_state() {
        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        val op = LedgerOperation(1, ProposedLedgerKind.SALE, "Juan", "100", unreadableFields = listOf(UnreadableLedgerField.AMOUNT))
        val reviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(op),
            generation = extracting.generation,
        )

        val state = reviewing.state as AiDebugLabState.LedgerReviewing
        assertEquals(1, state.review.summary.unregisterableOperationsCount)
        assertEquals(0, state.review.summary.registerableOperationsCount)

        val updatedModel = AiDebugLabMachine.onUpdateLedgerOperations(reviewing, "sess-1") { ops ->
            LedgerReviewEngine.setOperationAmount(ops, 1, "500")
        }

        val updatedState = updatedModel.state as AiDebugLabState.LedgerReviewing
        assertEquals(0, updatedState.review.summary.unregisterableOperationsCount)
        assertEquals(1, updatedState.review.summary.registerableOperationsCount)
        assertEquals(500L, updatedState.review.customerGroups.first().resultingBalanceCents)
    }

    // =========================================================================
    // RONDA 3 TESTS: Blocking confirmation, defensive copy, and trim parity
    // =========================================================================

    @Test
    fun ronda3_confirmation_blocked_when_financial_diagnostic_present() {
        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        val validOp = LedgerOperation(99, ProposedLedgerKind.SALE, "ValidCustomer", "1500")

        // 1. Zero amount: syntactically registerable but financially blocked
        // Paired with a valid executable operation (executableOperationsCount > 0) to isolate the financial error block.
        val zeroReviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(
                LedgerOperation(1, ProposedLedgerKind.SALE, "Juan", "0"),
                validOp,
            ),
            generation = extracting.generation,
        )
        val zeroState = zeroReviewing.state as AiDebugLabState.LedgerReviewing
        assertTrue(zeroState.review.summary.hasFinancialErrors)
        assertTrue("Must have at least 1 executable op to isolate financial error from zero-op check", zeroState.review.summary.executableOperationsCount > 0)
        val zeroOutcome = AiDebugLabMachine.onConfirmLedger(zeroReviewing, "sess-1", "biz-1")
        assertSame(AiLedgerConfirmOutcome.Ignored, zeroOutcome)

        // 2. Payment without debt: financially blocked
        val pmtReviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(
                LedgerOperation(1, ProposedLedgerKind.PAYMENT, "Maria", "500"),
                validOp,
            ),
            existingCustomerBalances = emptyMap(),
            generation = extracting.generation,
        )
        val pmtState = pmtReviewing.state as AiDebugLabState.LedgerReviewing
        assertTrue(pmtState.review.summary.hasFinancialErrors)
        assertTrue("Must have at least 1 executable op to isolate financial error from zero-op check", pmtState.review.summary.executableOperationsCount > 0)
        val pmtOutcome = AiDebugLabMachine.onConfirmLedger(pmtReviewing, "sess-1", "biz-1")
        assertSame(AiLedgerConfirmOutcome.Ignored, pmtOutcome)

        // 3. Payment exceeding debt: financially blocked
        val pmtExceedReviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(
                LedgerOperation(1, ProposedLedgerKind.PAYMENT, "Carlos", "1000"),
                validOp,
            ),
            existingCustomerBalances = mapOf(CustomerNameNormalizer.key("Carlos") to 400L),
            generation = extracting.generation,
        )
        val pmtExceedState = pmtExceedReviewing.state as AiDebugLabState.LedgerReviewing
        assertTrue(pmtExceedState.review.summary.hasFinancialErrors)
        assertTrue("Must have at least 1 executable op to isolate financial error from zero-op check", pmtExceedState.review.summary.executableOperationsCount > 0)
        val pmtExceedOutcome = AiDebugLabMachine.onConfirmLedger(pmtExceedReviewing, "sess-1", "biz-1")
        assertSame(AiLedgerConfirmOutcome.Ignored, pmtExceedOutcome)

        // 4. Arithmetic overflow: financially blocked
        val overflowReviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(
                LedgerOperation(1, ProposedLedgerKind.SALE, "OverflowUser", "100"),
                validOp,
            ),
            existingCustomerBalances = mapOf(CustomerNameNormalizer.key("OverflowUser") to Long.MAX_VALUE),
            generation = extracting.generation,
        )
        val overflowState = overflowReviewing.state as AiDebugLabState.LedgerReviewing
        assertTrue(overflowState.review.summary.hasFinancialErrors)
        assertTrue("Must have at least 1 executable op to isolate financial error from zero-op check", overflowState.review.summary.executableOperationsCount > 0)
        val overflowOutcome = AiDebugLabMachine.onConfirmLedger(overflowReviewing, "sess-1", "biz-1")
        assertSame(AiLedgerConfirmOutcome.Ignored, overflowOutcome)
    }

    @Test
    fun ronda3_confirmation_blocked_when_zero_executable_operations() {
        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")

        // Only unreadable operations -> 0 registerable, 0 executable
        val reviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(
                LedgerOperation(1, ProposedLedgerKind.SALE, "Juan", null, unreadableFields = listOf(UnreadableLedgerField.AMOUNT))
            ),
            generation = extracting.generation,
        )
        val state = reviewing.state as AiDebugLabState.LedgerReviewing
        assertEquals(0, state.review.summary.executableOperationsCount)
        assertFalse(state.review.summary.hasFinancialErrors)

        val outcome = AiDebugLabMachine.onConfirmLedger(reviewing, "sess-1", "biz-1")
        assertSame(AiLedgerConfirmOutcome.Ignored, outcome)
    }

    @Test
    fun ronda3_mutating_external_customer_balances_map_does_not_affect_state_or_subsequent_edits() {
        val externalBalances = mutableMapOf(
            CustomerNameNormalizer.key("Juan") to 5000L,
            CustomerNameNormalizer.key("Maria") to 2000L,
        )

        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        val reviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(
                LedgerOperation(1, ProposedLedgerKind.SALE, "Juan", "1000"),
                LedgerOperation(2, ProposedLedgerKind.SALE, "Maria", "500"),
            ),
            existingCustomerBalances = externalBalances,
            generation = extracting.generation,
        )

        val stateBefore = reviewing.state as AiDebugLabState.LedgerReviewing
        val juanGroupBefore = stateBefore.review.customerGroups.first { it.customerName == "Juan" }
        assertEquals(5000L, juanGroupBefore.previousBalanceCents)
        assertEquals(6000L, juanGroupBefore.resultingBalanceCents)
        assertFalse(juanGroupBefore.isNew)

        // Mutate the external map maliciously
        externalBalances.clear()
        externalBalances[CustomerNameNormalizer.key("Juan")] = 0L

        // State's copy must remain completely unaffected
        val juanGroupAfter = (reviewing.state as AiDebugLabState.LedgerReviewing).review.customerGroups.first { it.customerName == "Juan" }
        assertEquals(5000L, juanGroupAfter.previousBalanceCents)
        assertEquals(6000L, juanGroupAfter.resultingBalanceCents)
        assertFalse(juanGroupAfter.isNew)

        // Subsequent edit without passing balances must continue using preserved intact balances
        val edited = AiDebugLabMachine.onUpdateLedgerOperations(reviewing, "sess-1") { ops ->
            LedgerReviewEngine.setOperationAmount(ops, 1, "2000")
        }
        val editedState = edited.state as AiDebugLabState.LedgerReviewing
        val juanGroupEdited = editedState.review.customerGroups.first { it.customerName == "Juan" }
        assertEquals(5000L, juanGroupEdited.previousBalanceCents)
        assertEquals(7000L, juanGroupEdited.resultingBalanceCents)
        assertFalse(juanGroupEdited.isNew)
    }

    @Test
    fun ronda3_general_js_whitespace_trim_rejects_interleaved_bom() {
        val op = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "\uFEFF \uFEFF \uFEFF",
            amountCents = "100",
        )
        assertFalse("Interleaved BOM and whitespace must be rejected", isRegisterableLedgerOperation(op))
    }

    @Test
    fun batch_with_missing_dates_and_no_page_date_cannot_be_confirmed() {
        val opWithoutDate = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = "1000",
            date = null,
        )
        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        val reviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(opWithoutDate),
            generation = extracting.generation,
        )
        val state = reviewing.state as AiDebugLabState.LedgerReviewing
        assertTrue(state.review.summary.hasMissingDates)
        assertFalse(state.review.summary.canConfirm)

        val outcome = AiDebugLabMachine.onConfirmLedger(reviewing, "sess-1", "biz-1")
        assertSame(AiLedgerConfirmOutcome.Ignored, outcome)
    }

    @Test
    fun setting_valid_page_date_enables_confirmation() {
        val opWithoutDate = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = "1000",
            date = null,
        )
        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        val reviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(opWithoutDate),
            generation = extracting.generation,
        )
        val validPageDate = java.time.LocalDate.of(2026, 8, 15).toEpochDay()
        val updatedModel = AiDebugLabMachine.onUpdateLedgerPageDate(
            model = reviewing,
            sessionId = "sess-1",
            pageDateEpochDay = validPageDate,
            todayEpochDay = java.time.LocalDate.of(2026, 9, 1).toEpochDay(),
        )

        val state = updatedModel.state as AiDebugLabState.LedgerReviewing
        assertFalse(state.review.summary.hasMissingDates)
        assertTrue(state.review.summary.canConfirm)
        assertEquals(validPageDate, state.review.pageDateEpochDay)

        val outcome = AiDebugLabMachine.onConfirmLedger(updatedModel, "sess-1", "biz-1")
        assertTrue(outcome is AiLedgerConfirmOutcome.Started)
        val started = outcome as AiLedgerConfirmOutcome.Started
        assertEquals(validPageDate, started.review.pageDateEpochDay)
    }

    @Test
    fun editing_a_row_does_not_unblock_a_future_page_date() {
        val opWithoutDate = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = "1000",
            date = null,
        )
        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        val today = java.time.LocalDate.of(2026, 9, 1).toEpochDay()
        val futurePageDate = java.time.LocalDate.of(2026, 9, 2).toEpochDay()

        val reviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(opWithoutDate),
            generation = extracting.generation,
            todayEpochDay = today,
        )

        val modelWithFutureDate = AiDebugLabMachine.onUpdateLedgerPageDate(
            model = reviewing,
            sessionId = "sess-1",
            pageDateEpochDay = futurePageDate,
            todayEpochDay = today,
        )

        val futureState = modelWithFutureDate.state as AiDebugLabState.LedgerReviewing
        assertTrue(futureState.review.summary.hasMissingDates)
        assertFalse(futureState.review.summary.canConfirm)

        val modelAfterRowEdit = AiDebugLabMachine.onUpdateLedgerOperations(
            model = modelWithFutureDate,
            sessionId = "sess-1",
        ) { ops ->
            LedgerReviewEdits.applyAmount(ops, 1, "2000")
        }

        val stateAfterEdit = modelAfterRowEdit.state as AiDebugLabState.LedgerReviewing
        assertTrue(stateAfterEdit.review.summary.hasMissingDates)
        assertFalse(stateAfterEdit.review.summary.canConfirm)
    }

    @Test
    fun a_row_with_its_own_date_keeps_it_when_the_page_date_is_set() {
        // The rule the whole feature rests on: touching the page date can only ever fill the rows
        // the notebook did not date. A row with its own date is the paper saying when it happened,
        // and the page date is not allowed to overrule the paper.
        val ownDate = SafeDateCandidate(2026, 7, 2)
        val opWithOwnDate = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = "1000",
            date = ownDate,
        )
        val opWithoutDate = LedgerOperation(
            lineIndex = 2,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Ana",
            amountCents = "2000",
            date = null,
        )
        val today = java.time.LocalDate.of(2026, 9, 1).toEpochDay()
        val pageDate = java.time.LocalDate.of(2026, 7, 30).toEpochDay()

        assertEquals(java.time.LocalDate.of(2026, 7, 2).toEpochDay(), AiLedgerBatchPlanner.resolveDate(opWithOwnDate.date, pageDate, today))
        assertEquals(pageDate, AiLedgerBatchPlanner.resolveDate(opWithoutDate.date, pageDate, today))
    }

    @Test
    fun the_detected_page_date_travels_to_the_review_and_survives_edits() {
        // The offer cannot exist if the date does not survive the trip from the callable to the
        // card that offers it — including across the edits that rebuild the review.
        val op = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = "1000",
            date = null,
        )
        val detected = com.cuentivo.app.ai.ledger.LedgerDetectedPageDate(
            SafeDateCandidate(2026, 7, 30),
            0.95,
        )
        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        val reviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(op),
            generation = extracting.generation,
            detectedPageDate = detected,
        )

        val state = reviewing.state as AiDebugLabState.LedgerReviewing
        assertEquals(detected, state.review.detectedPageDate)

        // An edit rebuilds the review from scratch; the detected date is not a row, so it has to
        // be carried explicitly or the offer silently disappears after the first correction.
        val afterEdit = AiDebugLabMachine.onUpdateLedgerOperations(
            model = reviewing,
            sessionId = "sess-1",
        ) { ops ->
            LedgerReviewEdits.applyAmount(ops, 1, "2000")
        }
        val editedState = afterEdit.state as AiDebugLabState.LedgerReviewing
        assertEquals(detected, editedState.review.detectedPageDate)

        // And the same for a page date the person typed: the offer stays available until the
        // field stops being empty, which is the moment the person's reading replaces the model's.
        val afterPageDate = AiDebugLabMachine.onUpdateLedgerPageDate(
            model = afterEdit,
            sessionId = "sess-1",
            pageDateEpochDay = java.time.LocalDate.of(2026, 7, 30).toEpochDay(),
        )
        val pageDateState = afterPageDate.state as AiDebugLabState.LedgerReviewing
        assertEquals(detected, pageDateState.review.detectedPageDate)
    }

    @Test
    fun a_restored_review_rebuilds_with_the_persons_corrections_and_no_callable() {
        // The restore path: the saved half comes back from the Saver, the derivable half is
        // reloaded from Room, and the review that comes out is the one the person left — with the
        // row they corrected still corrected, and the doubt they did not touch still a doubt.
        // There is no callable in this function's signature on purpose: if restoring ever needs
        // the network, the defect this phase exists to remove is back.
        val corrected = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa Martinez",
            customerId = "cust-rosa",
            amountCents = "38000",
            date = null,
        )
        val doubtful = LedgerOperation(
            lineIndex = 2,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa Martnez",
            amountCents = "25000",
            date = null,
            uncertainFields = listOf(UncertainLedgerField.CUSTOMER),
        )
        val saved = com.cuentivo.app.ui.ai.LedgerReviewSaver.SavedLedgerReview(
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(corrected, doubtful),
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            pageDateEpochDay = java.time.LocalDate.of(2026, 7, 30).toEpochDay(),
            todayEpochDay = java.time.LocalDate.of(2026, 9, 1).toEpochDay(),
            detectedPageDate = null,
            originalOperations = listOf(corrected, doubtful),
        )

        val restored = AiDebugLabMachine.onRestoreLedgerReview(
            model = AiDebugLabModel(),
            sessionId = "sess-1",
            businessId = "biz-1",
            saved = saved,
            existingCustomerBalances = mapOf("rosa martinez" to 5000L),
            customerCandidates = emptyList(),
            productCandidates = emptyList(),
            registeredOperations = emptyList(),
        )

        val state = restored.state as AiDebugLabState.LedgerReviewing
        assertEquals("cust-rosa", state.review.operations[0].customerId)
        assertEquals("38000", state.review.operations[0].amountCents)
        assertEquals(listOf(UncertainLedgerField.CUSTOMER), state.review.operations[1].uncertainFields)
        assertEquals(saved.pageDateEpochDay, state.review.pageDateEpochDay)
        // The restored review is confirmable exactly as the live one was: the corrected row can
        // go in, the doubtful one cannot, and the page date resolves the rows without their own.
        assertTrue(state.review.summary.canConfirm)
    }

    @Test
    fun a_restore_never_lands_on_top_of_a_live_review() {
        // A restore arriving while a review is already on screen would overwrite fresher
        // corrections with the snapshot taken before them. The machine must refuse.
        val op = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = "1000",
            date = SafeDateCandidate(2026, 7, 2),
        )
        val initial = AiDebugLabModel()
        val extracting = AiDebugLabMachine.onStartLedgerExtraction(initial, "sess-1", "biz-1")
        val reviewing = AiDebugLabMachine.onLedgerExtractionResult(
            model = extracting,
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(op),
            generation = extracting.generation,
        )
        val saved = com.cuentivo.app.ui.ai.LedgerReviewSaver.SavedLedgerReview(
            sessionId = "sess-1",
            businessId = "biz-1",
            operations = listOf(op.copy(amountCents = "99999")),
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            pageDateEpochDay = null,
            todayEpochDay = java.time.LocalDate.of(2026, 9, 1).toEpochDay(),
            detectedPageDate = null,
            // La lectura original: lo que dio el modelo antes de que nadie tocara el monto.
            originalOperations = listOf(op),
        )

        val restored = AiDebugLabMachine.onRestoreLedgerReview(
            model = reviewing,
            sessionId = "sess-1",
            businessId = "biz-1",
            saved = saved,
            existingCustomerBalances = emptyMap(),
            customerCandidates = emptyList(),
            productCandidates = emptyList(),
            registeredOperations = emptyList(),
        )

        // The live review wins; the stale snapshot is not applied.
        assertSame(reviewing, restored)
        val state = restored.state as AiDebugLabState.LedgerReviewing
        assertEquals("1000", state.review.operations[0].amountCents)
    }

    /**
     * El bloque de la verificación cruzada pertenece a la PÁGINA, no a los renglones: cada edición
     * rebuilda la revisión desde cero, y el bloque tiene que sobrevivir el rebuild igual que la
     * fecha detectada. Sin esto, editar un renglón borraría las insignias del Ledger.
     */
    @Test
    fun el_bloque_del_ledger_sobrevive_al_rebuild_de_la_revision() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Toño",
            amountCents = "3800",
        )
        val crosscheck = MexalmCrosscheckPayload(
            agreed = false,
            disagreements = listOf(
                MexalmCrosscheckDisagreement(
                    lineIndex = 0,
                    field = MexalmCrosscheckDisagreementField.AMOUNT,
                    gemini = MexalmCrosscheckOperationSummary(amountCents = 3800L),
                    ledger = MexalmCrosscheckOperationSummary(amountCents = 3900L),
                ),
            ),
            ledgerStatus = "OK",
            latencyMs = 812L,
        )

        val review = LedgerReviewEngine.build(
            operations = listOf(op),
            mexalmCrosscheck = crosscheck,
        )
        assertEquals(crosscheck, review.mexalmCrosscheck)

        // El rebuild que hace una edición tiene que conservar el bloque.
        val rebuilt = LedgerReviewEngine.build(
            operations = LedgerReviewEngine.setOperationAmount(listOf(op), 0, "4000"),
            mexalmCrosscheck = review.mexalmCrosscheck,
        )
        assertEquals(crosscheck, rebuilt.mexalmCrosscheck)
        assertEquals("4000", rebuilt.operations[0].amountCents)
    }

    /**
     * Sin bloque no hay nada que mostrar, y la revisión se construye exactamente igual que antes.
     */
    @Test
    fun sin_bloque_la_revision_es_la_de_siempre() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "25000",
        )

        val review = LedgerReviewEngine.build(operations = listOf(op))

        assertEquals(null, review.mexalmCrosscheck)
        assertEquals(1, review.operations.size)
        assertEquals("25000", review.operations[0].amountCents)
    }
}
