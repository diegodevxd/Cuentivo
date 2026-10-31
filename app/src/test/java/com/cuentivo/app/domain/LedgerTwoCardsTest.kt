package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Cambiar todos" (año) y "Confirmar todos" (viejos) no pueden salir juntas por los mismos renglones (v47).
 *
 * Con la hoja real aparecían las dos: "12 renglones dicen 2023. ¿Los cambio a 2026?" y "12 renglones
 * son de hace más de 6 meses. ¿Son correctos?". Pulsar **"Confirmar todos"** primero aceptaba las
 * fechas de 2023 — justo lo que la tarjeta de año existe para impedir.
 *
 * Causa: el plan de año compara contra el año esperado (página, o **hoy** si la hoja no trae fecha),
 * pero el motivo de la objeción sólo comparaba contra la página. Sin fecha de página, un renglón de
 * 2023 no tenía con qué comparar el año, caía a "viejo", y las dos tarjetas lo contaban.
 */
class LedgerTwoCardsTest {

    private val hoy = LocalDate.of(2026, 9, 22)
    private val hoyEpochDay = hoy.toEpochDay()
    private val page = LocalDate.of(2026, 9, 10).toEpochDay()

    private fun row(lineIndex: Int, year: Int, month: Int, day: Int) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa $lineIndex",
        amountCents = "25000",
        rawText = "Rosa $lineIndex 250",
        date = SafeDateCandidate(year, month, day),
    )

    private fun objections(rows: List<LedgerOperation>, pageDateEpochDay: Long?) =
        LedgerDeterministicObjections.evaluate(
            operations = rows,
            existingCustomerCandidates = emptyList(),
            catalogProducts = emptyList(),
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = hoyEpochDay,
        )

    private val dateObjections = { rows: List<LedgerOperation>, pageDate: Long? ->
        objections(rows, pageDate).filter { it.type == LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE }
    }

    private fun yearCard(rows: List<LedgerOperation>, pageDate: Long? = page) =
        LedgerYearCorrection.plan(rows, objections(rows, pageDate), pageDate, hoyEpochDay)

    private fun oldCard(rows: List<LedgerOperation>, pageDate: Long? = page) =
        LedgerOldDatesBulkCorrection.lineIndices(objections(rows, pageDate))

    /** El mismo camino que la pantalla: "Cambiar todos" reescribe la fecha renglón por renglón. */
    private fun applyYearChange(rows: List<LedgerOperation>, pageDate: Long? = page): List<LedgerOperation> {
        val target = LedgerYearCorrection.targetYear(pageDate, hoyEpochDay)
        var updated = rows
        rows.forEach { op ->
            val corrected = LedgerYearCorrection.correctedDateFor(op, target) ?: return@forEach
            updated = LedgerReviewEdits.applyDate(updated, op.lineIndex, corrected, hoyEpochDay)
        }
        return updated
    }

    @Test
    fun `doce renglones de 2023 en hoja de 2026 solo levantan la tarjeta de anio`() {
        val rows = (0 until 12).map { row(it, 2023, 3, 1) }

        assertEquals(12, yearCard(rows)?.lineIndices?.size)
        assertTrue(
            "un renglón de año distinto no puede contar para 'Confirmar todos': lo suyo es el año",
            oldCard(rows).isEmpty(),
        )
    }

    @Test
    fun `sin fecha de pagina los renglones de 2023 tampoco cuentan como viejos`() {
        // Es el caso que producía las dos tarjetas: sin fecha de página, el motivo no comparaba el año.
        val rows = (0 until 12).map { row(it, 2023, 3, 1) }

        assertEquals(12, yearCard(rows, pageDate = null)?.lineIndices?.size)
        assertTrue(oldCard(rows, pageDate = null).isEmpty())
    }

    @Test
    fun `tras cambiar el anio queda sola la tarjeta de viejos`() {
        val rows = (0 until 12).map { row(it, 2023, 3, 1) }

        val corrected = applyYearChange(rows)

        assertNull("ya no hay años que corregir", yearCard(corrected))
        assertEquals(
            "y los doce siguen siendo viejos, pero ahora es lo único que queda por decir",
            (0 until 12).toList(),
            oldCard(corrected),
        )
    }

    @Test
    fun `mezcla de anio mal y viejo - cada tarjeta cuenta solo los suyos`() {
        val rows = listOf(
            row(0, 2023, 3, 1), row(1, 2023, 3, 2), row(2, 2023, 3, 3), // año mal
            row(3, 2026, 3, 1), row(4, 2026, 3, 2), row(5, 2026, 3, 3), // viejos, año correcto
        )

        assertEquals(listOf(0, 1, 2), yearCard(rows)?.lineIndices)
        assertEquals(listOf(3, 4, 5), oldCard(rows))
    }

    @Test
    fun `un viejo confirmado que luego cambia de anio vuelve a pedir lo que toca`() {
        var rows = listOf(row(0, 2026, 3, 1), row(1, 2026, 3, 2))
        assertEquals(listOf(0, 1), oldCard(rows))

        rows = LedgerReviewEdits.confirmObjection(rows, 0)
        rows = LedgerReviewEdits.confirmObjection(rows, 1)
        assertTrue(LedgerObjectionConfirmation.isConfirmed(rows[0]))

        // Corregir el año a otro equivocado anula la confirmación y devuelve el renglón a la tarjeta
        // de año, no a la de viejos.
        rows = LedgerReviewEdits.applyDate(rows, 0, SafeDateCandidate(2023, 3, 1), hoyEpochDay)

        assertFalse(LedgerObjectionConfirmation.isConfirmed(rows[0]))
        val reasonOfRowZero = dateObjections(rows, page).single { it.lineIndex == 0 }.dateReason
        assertEquals(LedgerImplausibleDateReason.YEAR_MISMATCH, reasonOfRowZero)
    }

    @Test
    fun `con un solo renglon de cada clase no hay tarjeta de anio ni de viejos`() {
        // Los umbrales no cambian con este arreglo: dos renglones mínimo en cada una.
        val rows = listOf(row(0, 2023, 3, 1), row(1, 2026, 3, 1))

        assertNull(yearCard(rows))
        assertTrue(oldCard(rows).isEmpty())
    }
}
