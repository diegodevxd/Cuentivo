package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La corrección del año mal leído, probada sin dispositivo.
 *
 * El defecto real: la IA leyó 2023 en los doce renglones de una hoja de 2026 y la pantalla dejaba
 * aceptarlo en un toque. Aquí se fija que el año correcto sale de la página (o de hoy), que la
 * fecha conserva mes y día, y —lo que más importa— que **un renglón sin objeción nunca entra al
 * plan**, aunque su año se vea raro.
 */
class LedgerYearCorrectionTest {

    private val today = LocalDate.of(2026, 9, 23).toEpochDay()
    private val pageDate = LocalDate.of(2026, 3, 10).toEpochDay()

    private fun op(lineIndex: Int, date: SafeDateCandidate?) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = "15000",
        date = date,
    )

    /** La objeción que sí habilita la corrección masiva: la fecha no es posible. */
    private fun dateObjection(lineIndex: Int) = LedgerDeterministicObjection(
        lineIndex = lineIndex,
        type = LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE,
    )

    private fun dateObjections(vararg lineIndices: Int) = lineIndices.map { dateObjection(it) }

    /** Una objeción de otro tipo: no autoriza tocar la fecha del renglón. */
    private fun priceObjection(lineIndex: Int) = LedgerDeterministicObjection(
        lineIndex = lineIndex,
        type = LedgerDeterministicObjectionType.PRICE_MISMATCH_SUSPECT,
    )

    @Test
    fun `el ano sale de la pagina y de hoy como respaldo`() {
        assertEquals(2026, LedgerYearCorrection.targetYear(pageDate, today))
        assertEquals(2026, LedgerYearCorrection.targetYear(null, today))
        // Una página de un año anterior corrige al año de la página, no al de hoy.
        val olderPage = LocalDate.of(2025, 12, 1).toEpochDay()
        assertEquals(2025, LedgerYearCorrection.targetYear(olderPage, today))
    }

    @Test
    fun `la fecha conserva mes y dia y solo cambia el ano`() {
        val corrected = LedgerYearCorrection.correctedDate(SafeDateCandidate(2023, 3, 10), 2026)
        assertEquals(SafeDateCandidate(2026, 3, 10), corrected)
    }

    @Test
    fun `el 29 de febrero cae al ultimo dia del mes en un ano no bisiesto`() {
        val corrected = LedgerYearCorrection.correctedDate(SafeDateCandidate(2024, 2, 29), 2026)
        assertEquals(SafeDateCandidate(2026, 2, 28), corrected)
        // Y en un bisiesto se conserva el 29.
        assertEquals(SafeDateCandidate(2028, 2, 29), LedgerYearCorrection.correctedDate(SafeDateCandidate(2024, 2, 29), 2028))
    }

    @Test
    fun `con un solo renglon no hay plan masivo`() {
        val operations = listOf(op(0, SafeDateCandidate(2023, 3, 10)))
        val plan = LedgerYearCorrection.plan(operations, dateObjections(0), pageDate, today)
        assertNull(plan)
    }

    @Test
    fun `con dos o mas renglones con objecion el plan los cubre a todos`() {
        val operations = listOf(
            op(0, SafeDateCandidate(2023, 3, 10)),
            op(1, SafeDateCandidate(2023, 4, 2)),
            op(2, SafeDateCandidate(2023, 5, 1)),
        )
        val plan = LedgerYearCorrection.plan(operations, dateObjections(0, 1, 2), pageDate, today)
        assertEquals(2023, plan?.readYear)
        assertEquals(2026, plan?.targetYear)
        assertEquals(listOf(0, 1, 2), plan?.lineIndices)
    }

    @Test
    fun `no toca renglones sin objecion aunque su ano se vea raro`() {
        // El renglón 1 trae el mismo año viejo, pero NO tiene objeción (por ejemplo, ya fue
        // confirmado a mano). El plan no puede tocarlo.
        val operations = listOf(
            op(0, SafeDateCandidate(2023, 3, 10)),
            op(1, SafeDateCandidate(2023, 4, 2)),
            op(2, SafeDateCandidate(2023, 5, 1)),
        )
        val plan = LedgerYearCorrection.plan(operations, dateObjections(0, 2), pageDate, today)
        assertEquals(listOf(0, 2), plan?.lineIndices)
    }

    @Test
    fun `un renglon con objecion pero ya en el ano de la pagina no entra al plan`() {
        val operations = listOf(
            op(0, SafeDateCandidate(2023, 3, 10)),
            op(1, SafeDateCandidate(2023, 4, 2)),
            // Objeción por antigüedad/futuridad, no por año: no hay nada que corregir aquí.
            op(2, SafeDateCandidate(2026, 8, 30)),
        )
        val plan = LedgerYearCorrection.plan(operations, dateObjections(0, 1, 2), pageDate, today)
        assertEquals(listOf(0, 1), plan?.lineIndices)
    }

    @Test
    fun `el ano leido dominante gana cuando se mezclan`() {
        val operations = listOf(
            op(0, SafeDateCandidate(2023, 3, 10)),
            op(1, SafeDateCandidate(2023, 4, 2)),
            op(2, SafeDateCandidate(2022, 5, 1)),
        )
        val plan = LedgerYearCorrection.plan(operations, dateObjections(0, 1, 2), pageDate, today)
        assertEquals(2023, plan?.readYear)
        assertTrue(plan?.hasMixedReadYears == true)
    }

    @Test
    fun `cuando todos los renglones comparten el ano leido no se marca mezcla`() {
        val operations = listOf(
            op(0, SafeDateCandidate(2023, 3, 10)),
            op(1, SafeDateCandidate(2023, 4, 2)),
        )
        val plan = LedgerYearCorrection.plan(operations, dateObjections(0, 1), pageDate, today)
        assertFalse(plan?.hasMixedReadYears == true)
    }

    /**
     * La regresión del recorrido: "Cambiar todos" corrige el año mal leído, y el año mal leído se
     * anuncia con `IMPLAUSIBLE_DATE`. Un renglón con otra objeción —aquí precio sospechoso— cuya
     * fecha no es la del año de la hoja pero es reciente y plausible **no** puede entrar al plan.
     * Si el filtro de tipo desapareciera, el renglón 2 se colaría y este caso se pondría rojo.
     */
    @Test
    fun `cambiar todos solo toca los renglones con objecion de fecha`() {
        // Jan 5 is intentional: Dec 31 in the previous year is only five days old and therefore
        // plausible under the production date objection policy when the page has no date.
        val scenarioToday = LocalDate.of(2026, 1, 5).toEpochDay()
        val operations = listOf(
            op(0, SafeDateCandidate(2023, 3, 10)),
            op(1, SafeDateCandidate(2023, 4, 2)),
            // Fecha plausible y reciente: cinco días antes de scenarioToday, sin año de página.
            op(2, SafeDateCandidate(2025, 12, 31)),
        )
        val actualDateObjections = LedgerDeterministicObjections.evaluate(
            operations = operations,
            existingCustomerCandidates = emptyList(),
            catalogProducts = emptyList(),
            pageDateEpochDay = null,
            todayEpochDay = scenarioToday,
        ).filter { it.type == LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE }
        assertEquals("the recent row must not also have a date objection", listOf(0, 1), actualDateObjections.map { it.lineIndex })

        val objections = actualDateObjections + priceObjection(2)
        val plan = LedgerYearCorrection.plan(
            operations = operations,
            objections = objections,
            pageDateEpochDay = null,
            todayEpochDay = scenarioToday,
        )

        assertEquals(listOf(0, 1), plan?.lineIndices)
        assertFalse("el renglón con objeción de precio no puede entrar", plan?.lineIndices?.contains(2) == true)
    }

    @Test
    fun `correctedDateFor devuelve null si el renglon no tiene fecha`() {
        assertNull(LedgerYearCorrection.correctedDateFor(op(0, null), 2026))
        assertEquals(
            SafeDateCandidate(2026, 7, 4),
            LedgerYearCorrection.correctedDateFor(op(0, SafeDateCandidate(2023, 7, 4)), 2026),
        )
    }
}
