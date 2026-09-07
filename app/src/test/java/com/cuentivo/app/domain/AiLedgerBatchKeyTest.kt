package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.data.local.CustomerEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two guards on the same failure mode: money that disappears without an error.
 *
 * A batch key collision means the second page is taken for a repeat of the first and never written,
 * silently. An overflowed opening balance means a legitimate payment is refused, or one larger than
 * the real debt is accepted. Neither shows up as a crash.
 */
class AiLedgerBatchKeyTest {

    private val businessId = "biz-1"
    private val userId = "user-1"
    private val todayEpochDay = LocalDate.of(2026, 9, 6).toEpochDay()
    private val nowEpochMillis = 1_757_184_000_000L
    private val date = LocalDate.of(2026, 9, 1).toEpochDay()

    private fun op(
        lineIndex: Int,
        customerName: String,
        description: String?,
        amountCents: String = "1000",
    ) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = customerName,
        amountCents = amountCents,
        description = description,
    )

    private fun keyOf(vararg ops: LedgerOperation): String =
        AiLedgerBatchPlanner.batchKeyFor(
            businessId = businessId,
            registrableOperations = ops.toList(),
            resolvedDatesByLineIndex = ops.associate { it.lineIndex to date },
        )

    @Test
    fun `free text cannot swallow a whole row and forge another page's key`() {
        // The old digest glued rows with "|" and fields with ":", both of which a person can type.
        // So a single row whose description carries the rest of the string encodes to exactly the
        // same bytes as two rows. The two-row page would be taken for a repeat of the one-row page
        // already written, and Beto's 1000 would never be registered -- no error, no crash, no trace.
        val twoRows = keyOf(
            op(0, customerName = "Ana", description = "jabon"),
            op(1, customerName = "Beto", description = "crema"),
        )
        val oneRowWearingTheOther = keyOf(
            op(0, customerName = "Ana", description = "jabon:$date|1:SALE:beto:1000:crema"),
        )

        assertNotEquals(twoRows, oneRowWearingTheOther)
    }

    @Test
    fun `the same page still produces the same key, or nothing would ever be idempotent`() {
        val first = keyOf(op(0, customerName = "Rosa", description = "protector"))
        val again = keyOf(op(0, customerName = "Rosa", description = "protector"))

        assertEquals(first, again)
    }

    @Test
    fun `changing an amount still changes the key`() {
        assertNotEquals(
            keyOf(op(0, "Rosa", "protector", amountCents = "1000")),
            keyOf(op(0, "Rosa", "protector", amountCents = "1001")),
        )
    }

    @Test
    fun `an opening balance that overflows is rejected instead of wrapping`() {
        val customer = CustomerEntity(
            id = "cust-1",
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
        // Two sales that individually fit in a Long and together do not. Summed with sumOf this
        // wraps to a negative balance, and the payment below is then refused as "no debt".
        val huge = listOf(
            OutstandingSale("s-1", Long.MAX_VALUE - 10L, null, date),
            OutstandingSale("s-2", 100L, null, date),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = listOf(
                LedgerOperation(
                    lineIndex = 0,
                    proposedKind = ProposedLedgerKind.PAYMENT,
                    customerName = "Rosa",
                    amountCents = "5000",
                ),
            ),
            existingCustomerMap = mapOf(customer.normalizedName to customer),
            existingOutstandingSalesByCustomerId = mapOf(customer.id to huge),
            pageDateEpochDay = date,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue("must reject, not plan against a wrapped balance", result is BatchPlanningResult.Rejected)
        val rejection = (result as BatchPlanningResult.Rejected).rejections.first()
        assertTrue(rejection is BatchPlanningRejection.FinancialError)
        assertEquals(
            com.cuentivo.app.ai.ledger.FinancialRejectionReason.OVERFLOW,
            (rejection as BatchPlanningRejection.FinancialError).reason,
        )
    }
}
