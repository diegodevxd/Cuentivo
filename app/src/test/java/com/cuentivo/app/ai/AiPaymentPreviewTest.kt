package com.cuentivo.app.ai

import com.cuentivo.app.data.local.CustomerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPaymentPreviewTest {

    private fun customer(
        id: String,
        name: String,
        businessId: String = "b1",
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
        isActive = true,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        deletedAtEpochMillis = null,
    )

    private fun candidate(id: String, name: String) = AiPaymentCustomerCandidate(id, name)

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
        assertEquals(
            SelectionRequired(rows),
            AiPaymentCandidateMatcher.match("María", rows),
        )
    }

    @Test
    fun `single partial row still requires selection`() {
        val rows = listOf(customer("1", "María López"))
        assertEquals(
            SelectionRequired(rows),
            AiPaymentCandidateMatcher.match("María", rows),
        )
    }

    @Test
    fun `no candidates is not found`() {
        assertEquals(
            AiPaymentCustomerMatch.NotFound,
            AiPaymentCandidateMatcher.match("María", emptyList()),
        )
    }

    @Test
    fun `punctuation in query is not stripped and prevents exact match`() {
        // CustomerNameNormalizer.key preserves leading/trailing punctuation, so a query wrapped in
        // inverted punctuation does not match the bare normalized name.
        val rows = listOf(customer("1", "Como Estas"))
        assertEquals(
            SelectionRequired(rows),
            AiPaymentCandidateMatcher.match("¿Cómo estás?", rows),
        )
    }

    @Test
    fun `exact match is unique even when a partial also exists`() {
        val rows = listOf(customer("1", "María"), customer("2", "María López"))
        assertEquals(
            AiPaymentCustomerMatch.Exact(customer("1", "María")),
            AiPaymentCandidateMatcher.match("María", rows),
        )
    }

    @Test
    fun `match preserves candidate order and copies the list defensively`() {
        val rows = listOf(customer("1", "María"), customer("2", "Maria"))
        val result = AiPaymentCandidateMatcher.match("María", rows)
        assertEquals(SelectionRequired(rows), result)
        if (result is SelectionRequired) {
            assertEquals(listOf("1", "2"), result.candidates.map { it.id })
        }
    }

    @Test
    fun `matcher selection result is an unmodifiable copy`() {
        val source = mutableListOf(customer("1", "María"), customer("2", "Maria"))
        val result = AiPaymentCandidateMatcher.match("María", source)
        assertTrue("expected SelectionRequired", result is SelectionRequired)
        val selection = result as SelectionRequired

        mutateExpectingUnsupported(selection.candidates as MutableList<CustomerEntity>)
        source.add(customer("3", "Extra"))
        assertEquals(2, selection.candidates.size)
    }

    @Test
    fun `SelectionRequired constructed directly is an unmodifiable copy`() {
        val source = mutableListOf(customer("1", "María"), customer("2", "Maria"))
        val result = SelectionRequired(source)

        mutateExpectingUnsupported(result.candidates as MutableList<CustomerEntity>)
        source.add(customer("3", "Extra"))
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun `CustomerSelectionRequired constructed directly is an unmodifiable copy`() {
        val source = mutableListOf(candidate("1", "María"), candidate("2", "Maria"))
        val result = CustomerSelectionRequired(source)

        mutateExpectingUnsupported(result.candidates as MutableList<AiPaymentCustomerCandidate>)
        source.add(candidate("3", "Extra"))
        assertEquals(2, result.candidates.size)
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

    private fun mutateExpectingUnsupported(candidates: MutableList<*>) {
        var thrown = false
        try {
            @Suppress("UNCHECKED_CAST")
            (candidates as MutableList<Any>).add(Any())
        } catch (expected: UnsupportedOperationException) {
            thrown = true
        }
        assertTrue("candidates must be unmodifiable", thrown)
    }
}