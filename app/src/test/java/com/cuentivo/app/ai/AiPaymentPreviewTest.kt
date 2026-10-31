package com.cuentivo.app.ai

import com.cuentivo.app.data.local.CustomerEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
            AiPaymentCustomerMatch.SelectionRequired(rows),
            AiPaymentCandidateMatcher.match("María", rows),
        )
    }

    @Test
    fun `single partial row still requires selection`() {
        val rows = listOf(customer("1", "María López"))
        assertEquals(
            AiPaymentCustomerMatch.SelectionRequired(rows),
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
            AiPaymentCustomerMatch.SelectionRequired(rows),
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
        assertEquals(AiPaymentCustomerMatch.SelectionRequired(rows), result)
        if (result is AiPaymentCustomerMatch.SelectionRequired) {
            assertEquals(listOf("1", "2"), result.candidates.map { it.id })
        }
    }

    @Test
    fun `matcher selection result is an unmodifiable copy`() {
        val source = mutableListOf(customer("1", "María"), customer("2", "Maria"))
        val result = AiPaymentCandidateMatcher.match("María", source)
        assertTrue("expected SelectionRequired", result is AiPaymentCustomerMatch.SelectionRequired)
        val selection = result as AiPaymentCustomerMatch.SelectionRequired

        mutateExpectingUnsupported(selection.candidates as MutableList<CustomerEntity>)
        source.add(customer("3", "Extra"))
        assertEquals(2, selection.candidates.size)
    }

    @Test
    fun `SelectionRequired constructed directly is an unmodifiable copy`() {
        val source = mutableListOf(customer("1", "María"), customer("2", "Maria"))
        val result = AiPaymentCustomerMatch.SelectionRequired(source)

        mutateExpectingUnsupported(result.candidates as MutableList<CustomerEntity>)
        source.add(customer("3", "Extra"))
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun `CustomerSelectionRequired constructed directly is an unmodifiable copy`() {
        val source = mutableListOf(candidate("1", "María"), candidate("2", "Maria"))
        val result = AiPaymentPreviewResult.CustomerSelectionRequired(source)

        mutateExpectingUnsupported(result.candidates as MutableList<AiPaymentCustomerCandidate>)
        source.add(candidate("3", "Extra"))
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun `declared amount and balance stay as Long with no floating point`() {
        val preview = AiResolvedPaymentPreview(
            confirmationKey = "key-1",
            customerId = "c1",
            customerName = "María",
            currentBalanceCents = 40_000L,
            declaredAmountCents = 15_000L,
            projectedBalanceCents = 25_000L,
        )
        assertEquals(25_000L, preview.projectedBalanceCents)
        assertEquals("key-1", preview.confirmationKey)
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

// ---- Task 2: read-only local payment preview resolver ----

private class RecordingSeams(
    val businessId: String = "b1",
    val searchResult: List<CustomerEntity> = emptyList(),
    var balanceResult: Long = 40_000L,
    var searchThrows: Boolean = false,
    var balanceThrows: Boolean = false,
) {
    var searchCalls: Int = 0
    var balanceCalls: Int = 0
    var lastSearchQuery: String? = null

    fun searchCustomers(businessId: String, query: String): List<CustomerEntity> {
        searchCalls += 1
        lastSearchQuery = query
        if (searchThrows) throw IllegalStateException("search down")
        return searchResult
    }

    fun loadBalanceCents(businessId: String, customerId: String): Long {
        balanceCalls += 1
        if (balanceThrows) throw IllegalStateException("balance down")
        return balanceResult
    }
}

class AiPaymentPreviewResolverTest {

    private val requestId = "123e4567-e89b-12d3-a456-426614174000"

    private fun customer(id: String = "c1", name: String = "María", businessId: String = "b1") = CustomerEntity(
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

    private fun proposal(amountCents: Long = 15_000L) =
        RecordPaymentProposal(customerName = "María", declaredAmountCents = amountCents)

    @Test
    fun `ready preview receives one opaque local confirmation key`() = runBlocking {
        var keyCalls = 0
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = { _, _ -> listOf(customer("c1", "María", "b1")) },
            loadBalanceCents = { _, _ -> 50_000L },
            newConfirmationKey = { keyCalls++; "ai-confirm-1" },
        )

        val result = resolver.resolve("b1", RecordPaymentProposal("María", 15_000L))

        assertTrue("expected Ready result", result is AiPaymentPreviewResult.Ready)
        val ready = result as AiPaymentPreviewResult.Ready
        assertEquals("ai-confirm-1", ready.preview.confirmationKey)
        assertEquals(1, keyCalls)
    }

    @Test
    fun `blocked and unresolved previews never allocate a confirmation key`() = runBlocking {
        var keyCalls = 0
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = { _, _ -> emptyList() },
            loadBalanceCents = { _, _ -> error("must not load") },
            newConfirmationKey = { keyCalls++; "forbidden" },
        )

        assertTrue(
            resolver.resolve("b1", RecordPaymentProposal("Nadie", 15_000L)) is AiPaymentPreviewResult.CustomerNotFound,
        )
        assertEquals(0, keyCalls)
    }

    @Test
    fun `blocked amount exceeds debt never allocates a confirmation key`() = runBlocking {
        var keyCalls = 0
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = { _, _ -> listOf(customer("c1", "María", "b1")) },
            loadBalanceCents = { _, _ -> 10_000L },
            newConfirmationKey = { keyCalls++; "forbidden" },
        )

        val result = resolver.resolve("b1", RecordPaymentProposal("María", 15_000L))
        assertTrue(result is AiPaymentPreviewResult.Blocked)
        assertEquals(0, keyCalls)
        val blocked = result as AiPaymentPreviewResult.Blocked
        assertEquals("", blocked.preview?.confirmationKey)
    }

    @Test
    fun `blank confirmation key fails closed to Unavailable`() = runBlocking {
        for (blankKey in listOf("", "   ", "\t\n")) {
            val resolver = AiPaymentPreviewResolver(
                searchCustomers = { _, _ -> listOf(customer("c1", "María", "b1")) },
                loadBalanceCents = { _, _ -> 50_000L },
                newConfirmationKey = { blankKey },
            )

            val result = resolver.resolve("b1", RecordPaymentProposal("María", 15_000L))
            assertEquals(AiPaymentPreviewResult.Unavailable, result)
        }
    }

    @Test
    fun `confirmation key is trimmed`() = runBlocking {
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = { _, _ -> listOf(customer("c1", "María", "b1")) },
            loadBalanceCents = { _, _ -> 50_000L },
            newConfirmationKey = { "  ai-confirm-trimmed  " },
        )

        val result = resolver.resolve("b1", RecordPaymentProposal("María", 15_000L))
        assertTrue(result is AiPaymentPreviewResult.Ready)
        val ready = result as AiPaymentPreviewResult.Ready
        assertEquals("ai-confirm-trimmed", ready.preview.confirmationKey)
    }

    @Test
    fun `blank business returns unavailable without invoking seams`() = runBlocking {
        val seams = RecordingSeams()
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = seams::searchCustomers,
            loadBalanceCents = seams::loadBalanceCents,
        )

        val result = resolver.resolve("", proposal())

        assertEquals(AiPaymentPreviewResult.Unavailable, result)
        assertEquals(0, seams.searchCalls)
        assertEquals(0, seams.balanceCalls)
    }

    @Test
    fun `non positive declared amount returns unavailable without invoking seams`() = runBlocking {
        for (amount in listOf(0L, -1L)) {
            val seams = RecordingSeams()
            val resolver = AiPaymentPreviewResolver(
                searchCustomers = seams::searchCustomers,
                loadBalanceCents = seams::loadBalanceCents,
            )
            assertEquals(AiPaymentPreviewResult.Unavailable, resolver.resolve("b1", proposal(amount)))
            assertEquals(0, seams.searchCalls)
            assertEquals(0, seams.balanceCalls)
        }
    }

    @Test
    fun `not found returns customer not found without loading balance`() = runBlocking {
        val seams = RecordingSeams(searchResult = emptyList())
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = seams::searchCustomers,
            loadBalanceCents = seams::loadBalanceCents,
        )

        val result = resolver.resolve("b1", proposal())

        assertEquals(AiPaymentPreviewResult.CustomerNotFound, result)
        assertEquals(1, seams.searchCalls)
        assertEquals(0, seams.balanceCalls)
    }

    @Test
    fun `selection required maps candidates to id and display name without loading balance`() = runBlocking {
        val candidates = listOf(
            CustomerEntity(
                id = "c1",
                businessId = "b1",
                name = "María López",
                normalizedName = "maria lopez",
                phone = null,
                email = null,
                address = null,
                notes = null,
                creditLimitCents = null,
                isActive = true,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                deletedAtEpochMillis = null,
            ),
        )
        val seams = RecordingSeams(searchResult = candidates)
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = seams::searchCustomers,
            loadBalanceCents = seams::loadBalanceCents,
        )

        val result = resolver.resolve("b1", proposal())

        assertTrue(result is AiPaymentPreviewResult.CustomerSelectionRequired)
        result as AiPaymentPreviewResult.CustomerSelectionRequired
        assertEquals(listOf(AiPaymentCustomerCandidate("c1", "María López")), result.candidates)
        assertEquals(0, seams.balanceCalls)
    }

    @Test
    fun `ready preview uses scoped search and real balance`() = runBlocking {
        var searchArgs: Pair<String, String>? = null
        var balanceArgs: Pair<String, String>? = null
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = { businessId, name ->
                searchArgs = businessId to name
                listOf(
                    CustomerEntity(
                        id = "c1",
                        businessId = businessId,
                        name = "María",
                        normalizedName = "maria",
                        phone = null,
                        email = null,
                        address = null,
                        notes = null,
                        creditLimitCents = null,
                        isActive = true,
                        createdAtEpochMillis = 0L,
                        updatedAtEpochMillis = 0L,
                        deletedAtEpochMillis = null,
                    ),
                )
            },
            loadBalanceCents = { businessId, customerId ->
                balanceArgs = businessId to customerId
                40_000L
            },
            newConfirmationKey = { "key-1" },
        )

        val result = resolver.resolve("business-1", RecordPaymentProposal("María", 15_000L))

        assertEquals("business-1" to "María", searchArgs)
        assertEquals("business-1" to "c1", balanceArgs)
        assertEquals(
            AiPaymentPreviewResult.Ready(AiResolvedPaymentPreview("key-1", "c1", "María", 40_000L, 15_000L, 25_000L)),
            result,
        )
    }

    @Test
    fun `exact payment yields projected zero`() = runBlocking {
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = { _, _ ->
                listOf(
                    CustomerEntity(
                        id = "c1",
                        businessId = "b1",
                        name = "María",
                        normalizedName = "maria",
                        phone = null,
                        email = null,
                        address = null,
                        notes = null,
                        creditLimitCents = null,
                        isActive = true,
                        createdAtEpochMillis = 0L,
                        updatedAtEpochMillis = 0L,
                        deletedAtEpochMillis = null,
                    ),
                )
            },
            loadBalanceCents = { _, _ -> 15_000L },
            newConfirmationKey = { "key-1" },
        )

        val result = resolver.resolve("b1", RecordPaymentProposal("María", 15_000L))

        assertEquals(
            AiPaymentPreviewResult.Ready(AiResolvedPaymentPreview("key-1", "c1", "María", 15_000L, 15_000L, 0L)),
            result,
        )
    }

    @Test
    fun `zero or negative balance blocks without preview`() = runBlocking {
        for (balance in listOf(0L, -5_000L)) {
            val resolver = AiPaymentPreviewResolver(
                searchCustomers = { _, _ ->
                    listOf(
                        CustomerEntity(
                            id = "c1",
                            businessId = "b1",
                            name = "María",
                            normalizedName = "maria",
                            phone = null,
                            email = null,
                            address = null,
                            notes = null,
                            creditLimitCents = null,
                            isActive = true,
                            createdAtEpochMillis = 0L,
                            updatedAtEpochMillis = 0L,
                            deletedAtEpochMillis = null,
                        ),
                    )
                },
                loadBalanceCents = { _, _ -> balance },
            )
            val result = resolver.resolve("b1", RecordPaymentProposal("María", 15_000L))
            assertEquals(
                AiPaymentPreviewResult.Blocked(AiPaymentBlockReason.NO_OUTSTANDING_DEBT, null),
                result,
            )
        }
    }

    @Test
    fun `amount greater than balance blocks with preview for display`() = runBlocking {
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = { _, _ ->
                listOf(
                    CustomerEntity(
                        id = "c1",
                        businessId = "b1",
                        name = "María",
                        normalizedName = "maria",
                        phone = null,
                        email = null,
                        address = null,
                        notes = null,
                        creditLimitCents = null,
                        isActive = true,
                        createdAtEpochMillis = 0L,
                        updatedAtEpochMillis = 0L,
                        deletedAtEpochMillis = null,
                    ),
                )
            },
            loadBalanceCents = { _, _ -> 10_000L },
        )

        val result = resolver.resolve("b1", RecordPaymentProposal("María", 15_000L))

        assertEquals(
            AiPaymentPreviewResult.Blocked(
                AiPaymentBlockReason.AMOUNT_EXCEEDS_DEBT,
                AiResolvedPaymentPreview("", "c1", "María", 10_000L, 15_000L, -5_000L),
            ),
            result,
        )
    }

    @Test
    fun `seam exception returns unavailable`() = runBlocking {
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = { _, _ -> throw IllegalStateException("search down") },
            loadBalanceCents = { _, _ -> 40_000L },
        )
        assertEquals(AiPaymentPreviewResult.Unavailable, resolver.resolve("b1", proposal()))

        val resolver2 = AiPaymentPreviewResolver(
            searchCustomers = { _, _ ->
                listOf(
                    CustomerEntity(
                        id = "c1",
                        businessId = "b1",
                        name = "María",
                        normalizedName = "maria",
                        phone = null,
                        email = null,
                        address = null,
                        notes = null,
                        creditLimitCents = null,
                        isActive = true,
                        createdAtEpochMillis = 0L,
                        updatedAtEpochMillis = 0L,
                        deletedAtEpochMillis = null,
                    ),
                )
            },
            loadBalanceCents = { _, _ -> throw IllegalStateException("balance down") },
        )
        assertEquals(AiPaymentPreviewResult.Unavailable, resolver2.resolve("b1", proposal()))
    }

    @Test
    fun `extreme long values are handled without crash`() = runBlocking {
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = { _, _ ->
                listOf(
                    CustomerEntity(
                        id = "c1",
                        businessId = "b1",
                        name = "María",
                        normalizedName = "maria",
                        phone = null,
                        email = null,
                        address = null,
                        notes = null,
                        creditLimitCents = null,
                        isActive = true,
                        createdAtEpochMillis = 0L,
                        updatedAtEpochMillis = 0L,
                        deletedAtEpochMillis = null,
                    ),
                )
            },
            loadBalanceCents = { _, _ -> Long.MAX_VALUE },
            newConfirmationKey = { "key-max" },
        )

        // Long.MAX_VALUE - Long.MAX_VALUE = 0: mathematically valid, no overflow possible
        // with two positive Long operands. The system must not crash.
        val result = resolver.resolve("b1", RecordPaymentProposal("María", Long.MAX_VALUE))
        assertTrue(result is AiPaymentPreviewResult.Ready)
        val ready = result as AiPaymentPreviewResult.Ready
        assertEquals(0L, ready.preview.projectedBalanceCents)
        assertEquals("key-max", ready.preview.confirmationKey)
    }

    @Test
    fun `cancellation exception is rethrown not swallowed`() = runBlocking {
        val resolver = AiPaymentPreviewResolver(
            searchCustomers = { _, _ -> throw CancellationException("cancelled") },
            loadBalanceCents = { _, _ -> 40_000L },
        )
        try {
            resolver.resolve("b1", proposal())
            throw AssertionError("expected CancellationException")
        } catch (expected: CancellationException) {
            // expected
        }
    }
}
