package com.cuentivo.app.ui.product

import com.cuentivo.app.domain.AccessDecision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The transition itself, not just the decision to start.
 *
 * `ProductImportPolicyTest` proves a blocked plan never begins; this proves that once an import
 * does begin the flag always comes back down — including on the unreadable-file route, which is
 * exactly the one that used to strand the button on "Importando…".
 */
class ProductImportRunnerTest {
    private val transitions = mutableListOf<Boolean>()

    @Test
    fun a_successful_import_raises_then_lowers_the_flag() = runBlocking {
        val outcome = ProductImportRunner.runIfStarted(BEGIN, ::record) { businessId ->
            assertEquals("business-1", businessId)
            "imported"
        }

        assertEquals(listOf(true, false), transitions)
        assertEquals("imported", outcome?.getOrNull())
    }

    @Test
    fun an_unreadable_file_still_lowers_the_flag_and_reports_the_failure() = runBlocking {
        val boom = IllegalStateException("No se pudo abrir el archivo")

        val outcome = ProductImportRunner.runIfStarted(BEGIN, ::record) { throw boom }

        // The whole point: the finally runs even though the block threw.
        assertEquals(listOf(true, false), transitions)
        assertTrue(outcome!!.isFailure)
        assertEquals(boom, outcome.exceptionOrNull())
    }

    @Test
    fun a_failure_after_partial_work_still_lowers_the_flag() = runBlocking {
        var inserted = 0

        val outcome = ProductImportRunner.runIfStarted(BEGIN, ::record) {
            inserted++
            error("row 2 is malformed")
        }

        assertEquals(1, inserted)
        assertEquals(listOf(true, false), transitions)
        assertTrue(outcome!!.isFailure)
    }

    @Test
    fun a_cancellation_propagates_instead_of_becoming_a_failed_result() = runBlocking {
        val cancellation = CancellationException("the scope went away")
        var caught: Throwable? = null

        try {
            ProductImportRunner.runIfStarted(BEGIN, ::record) { throw cancellation }
            fail("a cancellation must not be swallowed into a Result")
        } catch (thrown: CancellationException) {
            caught = thrown
        }

        // The flag still comes back down, because the finally runs on the way out.
        assertEquals(listOf(true, false), transitions)
        // And it is the very same exception, so the parent job still learns it was cancelled.
        assertSame(cancellation, caught)
    }

    @Test
    fun a_blocked_plan_never_touches_the_flag() = runBlocking {
        var ran = false

        val outcome = ProductImportRunner.runIfStarted(ImportStart.Denied(BLOCKED), ::record) {
            ran = true
        }

        assertNull(outcome)
        assertEquals(emptyList<Boolean>(), transitions)
        assertEquals(false, ran)
    }

    @Test
    fun no_active_business_never_touches_the_flag() = runBlocking {
        var ran = false

        val outcome = ProductImportRunner.runIfStarted(ImportStart.NoBusiness, ::record) {
            ran = true
        }

        assertNull(outcome)
        assertEquals(emptyList<Boolean>(), transitions)
        assertEquals(false, ran)
    }

    private fun record(value: Boolean) {
        transitions += value
    }

    private companion object {
        val BEGIN = ImportStart.Begin("business-1")
        val BLOCKED = AccessDecision.Blocked(
            title = "Función de Cuentivo Pro",
            detail = "Importar tu catálogo está incluido en Cuentivo Pro.",
        )
    }
}
