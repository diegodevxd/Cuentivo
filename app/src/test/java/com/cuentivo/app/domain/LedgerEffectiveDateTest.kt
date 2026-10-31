package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2 · La fecha efectiva y la ventana de consulta (v44).
 *
 * El detector compara contra la fecha efectiva de cada renglón —la del renglón, o la de la página si
 * el renglón no trae—, pero la consulta a Room usaba sólo fechas de renglón y caía a hoy ±14. Una
 * página de 2023 sin fechas por renglón consultaba 2026 y no encontraba nada. Aquí se fija la única
 * definición de "fecha efectiva" y de "ventana", que usan los dos.
 */
class LedgerEffectiveDateTest {

    private val today = LocalDate.of(2026, 9, 22).toEpochDay()
    private val page2023 = LocalDate.of(2023, 3, 1).toEpochDay()

    private fun row(lineIndex: Int, date: SafeDateCandidate? = null) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Laura",
        amountCents = "99900",
        date = date,
    )

    @Test
    fun `la fecha del renglon manda sobre la de la pagina`() {
        val op = row(0, SafeDateCandidate(2026, 8, 14))
        assertEquals(
            LocalDate.of(2026, 8, 14).toEpochDay(),
            LedgerEffectiveDate.of(op, pageDateEpochDay = page2023, todayEpochDay = today),
        )
    }

    @Test
    fun `sin fecha de renglon se usa la de la pagina`() {
        val op = row(0, null)
        assertEquals(
            page2023,
            LedgerEffectiveDate.of(op, pageDateEpochDay = page2023, todayEpochDay = today),
        )
    }

    @Test
    fun `sin fecha de renglon ni de pagina no hay fecha efectiva`() {
        assertNull(LedgerEffectiveDate.of(row(0, null), pageDateEpochDay = null, todayEpochDay = today))
    }

    @Test
    fun `una fecha futura no cuenta como efectiva`() {
        val future = SafeDateCandidate(2027, 1, 1)
        assertNull(LedgerEffectiveDate.of(row(0, future), pageDateEpochDay = null, todayEpochDay = today))
    }

    @Test
    fun `la ventana cubre la fecha de la pagina cuando los renglones no traen fecha`() {
        // El escenario de Codex: página de 2023, renglones sin fecha. La ventana tiene que caer en
        // 2023, no en 2026.
        val (min, max) = LedgerEffectiveDate.queryWindow(
            operations = listOf(row(0, null), row(1, null)),
            pageDateEpochDay = page2023,
            todayEpochDay = today,
            windowDays = 14L,
        )
        assertEquals(page2023 - 14L, min)
        assertEquals(page2023 + 14L, max)
    }

    @Test
    fun `sin ninguna fecha efectiva la ventana cae a hoy`() {
        val (min, max) = LedgerEffectiveDate.queryWindow(
            operations = listOf(row(0, null)),
            pageDateEpochDay = null,
            todayEpochDay = today,
            windowDays = 14L,
        )
        assertEquals(today - 14L, min)
        assertEquals(today + 14L, max)
    }

    @Test
    fun `la ventana abarca el minimo y el maximo de las fechas efectivas`() {
        val (min, max) = LedgerEffectiveDate.queryWindow(
            operations = listOf(
                row(0, SafeDateCandidate(2026, 8, 14)),
                row(1, null), // hereda la página de 2023
            ),
            pageDateEpochDay = page2023,
            todayEpochDay = today,
            windowDays = 14L,
        )
        assertEquals(page2023 - 14L, min)
        assertEquals(LocalDate.of(2026, 8, 14).toEpochDay() + 14L, max)
    }
}
