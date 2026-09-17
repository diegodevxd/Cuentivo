package com.cuentivo.app.domain

import com.cuentivo.app.data.local.CustomerEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on the page's identity key.
 *
 * The key is a pure function of `(businessId, importSessionId)`. Content is deliberately NOT part of
 * it: amounts, customers, dates and descriptions are editable after a commit whose result was lost,
 * and any one of them would produce a different key and write the same charge twice.
 *
 * Because of that, nothing in this file can assert anything *about content* — the function cannot see
 * it, so such an assertion could never fail and would be a green that means nothing. The guard for
 * the failure that actually matters (a lost commit being recognised on retry) lives where it can
 * fail: `LedgerSessionKeyIdempotencyTest`, which drives the coordinator with the same session and
 * edited content.
 *
 * The last test is the other money guard: an overflowed opening balance means a legitimate payment is
 * refused, or one larger than the real debt is accepted. Neither shows up as a crash.
 */
class AiLedgerBatchKeyTest {

    private val businessId = "biz-1"
    private val userId = "user-1"
    private val todayEpochDay = LocalDate.of(2026, 9, 6).toEpochDay()
    private val nowEpochMillis = 1_757_184_000_000L
    private val date = LocalDate.of(2026, 9, 1).toEpochDay()

    private fun keyOf(
        businessId: String = this.businessId,
        importSessionId: String = "session-test",
    ): String = AiLedgerBatchPlanner.batchKeyFor(businessId, importSessionId)

    @Test
    fun `the same session and business produce the same key, or nothing would ever be idempotent`() {
        assertEquals(keyOf(), keyOf())
    }

    @Test
    fun `a different session produces a different key, so a second photo is its own page`() {
        // Dos fotos de la misma libreta son dos páginas legítimas. Si compartieran clave, la segunda
        // se tomaría por una repetición de la primera y nunca se escribiría, en silencio.
        assertNotEquals(keyOf(importSessionId = "session-1"), keyOf(importSessionId = "session-2"))
    }

    @Test
    fun `a different business produces a different key for the same session`() {
        assertNotEquals(keyOf(businessId = "biz-1"), keyOf(businessId = "biz-2"))
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
            importSessionId = "session-test",
            operations = listOf(
                com.cuentivo.app.ai.ledger.LedgerOperation(
                    lineIndex = 0,
                    proposedKind = com.cuentivo.app.ai.ledger.ProposedLedgerKind.PAYMENT,
                    customerName = "Rosa",
                    amountCents = "5000",
                ),
            ),
            blockedLineIndices = emptySet(),
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
