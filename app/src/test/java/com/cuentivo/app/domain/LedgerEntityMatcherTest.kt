package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.UnreadableLedgerField
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class LedgerEntityMatcherTest {

    @Test
    fun `protect tap with price match yields AUTOMATIC product match`() {
        val candidates = listOf(
            ProductCandidate(id = "p1", name = "Protector Tapete", salePriceCents = 7990L),
            ProductCandidate(id = "p2", name = "Coca Cola 600ml", salePriceCents = 2000L),
        )

        val result = LedgerEntityMatcher.matchProduct("Protect tap", 7990L, candidates)

        assertEquals(MatchDecision.AUTOMATIC, result.decision)
        assertEquals("p1", result.candidate?.id)
        assertEquals("Protector Tapete", result.candidate?.name)
    }

    @Test
    fun `protect tap without matching price yields SUGGESTED or text match`() {
        val candidates = listOf(
            ProductCandidate(id = "p1", name = "Protector Tapete", salePriceCents = 9500L),
            ProductCandidate(id = "p2", name = "Coca Cola", salePriceCents = 2000L),
        )

        val result = LedgerEntityMatcher.matchProduct("Protect tap", 7990L, candidates)

        // Without the exact price boost, score is around 0.85 (SUGGESTED), not AUTOMATIC
        assertEquals(MatchDecision.SUGGESTED, result.decision)
        assertEquals("p1", result.candidate?.id)
    }

    @Test
    fun `mayra T5 exact customer match yields AUTOMATIC`() {
        val candidates = listOf(
            CustomerCandidate(id = "c1", name = "Mayra T5", hasRecentActivity = true),
            CustomerCandidate(id = "c2", name = "Juan Perez", hasRecentActivity = false),
        )

        val result = LedgerEntityMatcher.matchCustomer("Mayra T5", candidates)

        assertEquals(MatchDecision.AUTOMATIC, result.decision)
        assertEquals("c1", result.candidate?.id)
        assertEquals(1.0, result.score, 0.001)
    }

    @Test
    fun `srenza TS against Sirenza TS and Sandy yields SUGGESTED not AUTOMATIC`() {
        val candidates = listOf(
            CustomerCandidate(id = "c1", name = "Sirenza TS"),
            CustomerCandidate(id = "c2", name = "Sandy"),
        )

        val result = LedgerEntityMatcher.matchCustomer("Srenza TS", candidates)

        // A single character difference produces a high score (~0.85-0.89) -> SUGGESTED, NOT AUTOMATIC
        assertEquals(MatchDecision.SUGGESTED, result.decision)
        assertEquals("c1", result.candidate?.id)
    }

    @Test
    fun `two customer candidates tied in score yields UNRESOLVED`() {
        val candidates = listOf(
            CustomerCandidate(id = "c1", name = "Juan Perez"),
            CustomerCandidate(id = "c2", name = "Juan Perez"),
        )

        val result = LedgerEntityMatcher.matchCustomer("Juan Perez", candidates)

        // Tie in top scores -> margin is 0 -> UNRESOLVED
        assertEquals(MatchDecision.UNRESOLVED, result.decision)
        assertNull(result.candidate)
    }

    @Test
    fun `empty catalog yields UNRESOLVED`() {
        val customerResult = LedgerEntityMatcher.matchCustomer("Rosa", emptyList())
        assertEquals(MatchDecision.UNRESOLVED, customerResult.decision)
        assertNull(customerResult.candidate)

        val productResult = LedgerEntityMatcher.matchProduct("Coca", 2000L, emptyList())
        assertEquals(MatchDecision.UNRESOLVED, productResult.decision)
        assertNull(productResult.candidate)
    }

    @Test
    fun `blank name query yields UNRESOLVED`() {
        val candidates = listOf(CustomerCandidate(id = "c1", name = "Rosa"))
        val result = LedgerEntityMatcher.matchCustomer("   ", candidates)
        assertEquals(MatchDecision.UNRESOLVED, result.decision)
        assertNull(result.candidate)
    }

    @Test
    fun `the amount NEVER changes even on AUTOMATIC match`() {
        val candidates = listOf(
            ProductCandidate(id = "p1", name = "Protector Tapete", salePriceCents = 7990L),
        )

        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "8500", // Notebook says 85.00
            description = "Protect tap",
        )

        val match = LedgerEntityMatcher.matchProduct(op.description, 8500L, candidates)

        // The matcher returns the candidate and decision, but NEVER alters op.amountCents!
        assertEquals("8500", op.amountCents)
        assertEquals("p1", match.candidate?.id)
    }

    @Test
    fun `a match does NOT make an unreadable operation registerable`() {
        val opWithUnreadableCustomer = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = null,
            amountCents = "5000",
            unreadableFields = listOf(UnreadableLedgerField.CUSTOMER),
        )

        // Matcher runs, but isRegisterableLedgerOperation remains FALSE until explicit human edit
        val candidates = listOf(CustomerCandidate(id = "c1", name = "Rosa"))
        val match = LedgerEntityMatcher.matchCustomer("Rosa", candidates)

        assertEquals(MatchDecision.AUTOMATIC, match.decision)
        assertFalse(isRegisterableLedgerOperation(opWithUnreadableCustomer))
    }

    /**
     * A smoke alarm for an algorithmic regression, not a benchmark.
     *
     * The bound is deliberately loose. A tight wall-clock assertion in a unit suite fails on a busy
     * machine, and a test that fails at random gets ignored -- at which point it protects nothing.
     * What it still catches is the failure that matters: somebody turning this into something
     * quadratic, which at a real catalogue size would freeze the review screen rather than slow it.
     *
     * Fixed seed and a warm-up pass, so a cold JIT is not measured and two runs compare the same work.
     */
    @Test
    fun `matching a real sized catalogue does not blow up`() {
        val random = kotlin.random.Random(seed = 7)
        val products = (1..552).map { i ->
            ProductCandidate(
                id = "p_$i",
                name = "Producto de Catalogo Numero $i Especial",
                salePriceCents = random.nextLong(100L, 5000L),
            )
        }
        val queries = (1..25).map { i -> "Producto $i" to 1500L }

        // Warm-up, discarded.
        for ((query, amount) in queries) {
            LedgerEntityMatcher.matchProduct(query, amount, products)
        }

        val elapsed = measureTimeMillis {
            for ((query, amount) in queries) {
                LedgerEntityMatcher.matchProduct(query, amount, products)
            }
        }

        assertTrue(
            "25 renglones contra 552 productos tardaron $elapsed ms: eso ya no es ruido de maquina",
            elapsed < 2_000,
        )
    }
}
