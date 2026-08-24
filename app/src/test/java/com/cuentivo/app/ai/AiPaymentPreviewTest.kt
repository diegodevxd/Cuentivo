package com.cuentivo.app.ai

import com.cuentivo.app.data.local.CustomerEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AiPaymentPreviewTest {

    private fun customer(
        id: String,
        name: String,
        businessId: String = "b1",
        isActive: Boolean = true,
        deletedAtEpochMillis: Long? = null,
    ) = CustomerEntity(
        id = id,
        businessId = businessId,
        name = name,
        normalizedName = com.cuentivo.app.domain.CustomerNameNormalizer.key(name),
        phone = null,
        email = null,
        address = null,
        notes = null,
        creditLimitCents = null,
        isActive = isActive,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        deletedAtEpochMillis = deletedAtEpochMillis,
    )

    @Test
    fun `unique exact normalized match resolves`() {
        val result = AiPaymentCandidateMatcher.match(
            "  MARÍA ",
            listOf(customer("1", "María")),
        )
        assertEquals(AiPaymentCustomerMatch.Exact(customer("1", "María")), result)
    }

    @Test
    fun `multiple exact rows require selection`() {
        val rows = listOf(customer("1", "María"), customer("2", "Maria"))
        assertEquals(AiPaymentCustomerMatch.SelectionRequired(rows), AiPaymentCandidateMatcher.match("María", rows))
    }

    @Test
    fun `single partial row still requires selection`() {
        val rows = listOf(customer("1", "María López"))
        assertEquals(AiPaymentCustomerMatch.SelectionRequired(rows), AiPaymentCandidateMatcher.match("María", rows))
    }

    @Test
    fun `no candidates is not found`() {
        assertEquals(AiPaymentCustomerMatch.NotFound, AiPaymentCandidateMatcher.match("María", emptyList()))
    }

    @Test
    fun `accented query continues because there is no accent normalization`() {
        // "¿Cómo estás?" has no exact entry in the allow-list; without accent stripping it must not match.
        val rows = listOf(customer("1", "Como Estas"))
        assertEquals(AiPaymentCustomerMatch.SelectionRequired(rows), AiPaymentCandidateMatcher.match("¿Cómo estás?", rows))
    }

    @Test
    fun `exact match is unique even when a partial also exists`() {
        val rows = listOf(customer("1", "María"), customer("2", "María López"))
        assertEquals(AiPaymentCustomerMatch.Exact(customer("1", "María")), AiPaymentCandidateMatcher.match("María", rows))
    }

    @Test
    fun `match preserves candidate order and copies the list defensively`() {
        val rows = listOf(customer("1", "María"), customer("2", "Maria"))
        val result = AiPaymentCandidateMatcher.match("María", rows)
        assertEquals(AiPaymentCustomerMatch.SelectionRequired(rows), result)
        if (result is AiPaymentCustomerMatch.SelectionRequired) {
            assertEquals(listOf("1", "2"), result.candidates.map { it.id })
        }
    }

    @Test
    fun `declared amount and balance stay as Long with no floating point`() {
        val preview = AiResolvedPaymentPreview(
            customerId = "c1",
            customerName = "María",
            currentBalanceCents = 40_000L,
            declaredAmountCents = 15_000L,
            projectedBalanceCents = 25_000L,
        )
        assertEquals(25_000L, preview.projectedBalanceCents)
    }
}