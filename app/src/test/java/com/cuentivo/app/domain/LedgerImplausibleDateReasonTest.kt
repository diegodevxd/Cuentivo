package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El motivo de una fecha implausible, no sólo que lo sea (v46).
 *
 * Antes los tres casos —año mal leído, fecha vieja de verdad y fecha futura— compartían un solo texto
 * que mandaba a "revisa el año". Con una libreta de deudas de hace meses, el año ya estaba bien y el
 * aviso seguía pidiendo lo mismo en los doce renglones.
 */
class LedgerImplausibleDateReasonTest {

    private val hoy = LocalDate.of(2026, 9, 22)

    private fun candidate(day: LocalDate) = SafeDateCandidate(day.year, day.monthValue, day.dayOfMonth)

    private fun row(lineIndex: Int, date: SafeDateCandidate?) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = "25000",
        rawText = "Rosa 250",
        date = date,
    )

    private fun reasons(
        rows: List<LedgerOperation>,
        pageDateEpochDay: Long?,
        todayEpochDay: Long? = hoy.toEpochDay(),
    ) = LedgerDeterministicObjections.evaluate(
        operations = rows,
        existingCustomerCandidates = emptyList(),
        catalogProducts = emptyList(),
        pageDateEpochDay = pageDateEpochDay,
        todayEpochDay = todayEpochDay,
    ).filter { it.type == LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE }

    private val page = LocalDate.of(2026, 9, 10).toEpochDay()

    @Test
    fun `un anio distinto al de la hoja es YEAR_MISMATCH`() {
        val resultado = reasons(listOf(row(0, SafeDateCandidate(2023, 3, 1))), pageDateEpochDay = page)

        assertEquals(1, resultado.size)
        assertEquals(LedgerImplausibleDateReason.YEAR_MISMATCH, resultado.single().dateReason)
    }

    @Test
    fun `181 dias atras en el mismo anio es TOO_OLD`() {
        val resultado = reasons(listOf(row(0, candidate(hoy.minusDays(181)))), pageDateEpochDay = page)

        assertEquals(1, resultado.size)
        assertEquals(LedgerImplausibleDateReason.TOO_OLD, resultado.single().dateReason)
    }

    @Test
    fun `180 dias exactos no objeta`() {
        assertTrue(reasons(listOf(row(0, candidate(hoy.minusDays(180)))), pageDateEpochDay = page).isEmpty())
    }

    @Test
    fun `manana es FUTURE`() {
        val resultado = reasons(listOf(row(0, candidate(hoy.plusDays(1)))), pageDateEpochDay = hoy.toEpochDay())

        assertEquals(1, resultado.size)
        assertEquals(LedgerImplausibleDateReason.FUTURE, resultado.single().dateReason)
    }

    @Test
    fun `hoy mismo no objeta`() {
        assertTrue(reasons(listOf(row(0, candidate(hoy))), pageDateEpochDay = hoy.toEpochDay()).isEmpty())
    }

    @Test
    fun `el anio manda sobre la antiguedad - 2023 en hoja de 2026 es YEAR_MISMATCH, no TOO_OLD`() {
        // Es el caso de Diego: el renglón también es viejo, pero decir "hace más de 6 meses" no
        // ayuda cuando lo que está mal es el año, y "Cambiar todos" es el arreglo correcto.
        val resultado = reasons(listOf(row(0, SafeDateCandidate(2023, 3, 1))), pageDateEpochDay = page)

        assertEquals(LedgerImplausibleDateReason.YEAR_MISMATCH, resultado.single().dateReason)
    }

    @Test
    fun `una fecha futura de otro anio es YEAR_MISMATCH - el anio es lo accionable`() {
        val resultado = reasons(listOf(row(0, SafeDateCandidate(2027, 1, 5))), pageDateEpochDay = hoy.toEpochDay())

        assertEquals(1, resultado.size)
        assertEquals(LedgerImplausibleDateReason.YEAR_MISMATCH, resultado.single().dateReason)
    }

    @Test
    fun `sin fecha de pagina el anio se compara contra hoy, no contra nada`() {
        // v47: con la hoja sin fecha, la tarjeta de año corrige contra el año de hoy. El motivo tiene
        // que decir lo mismo, o el renglón sale como "hace más de 6 meses" Y cuenta para "Cambiar
        // todos", y las dos tarjetas aparecen juntas por los mismos renglones.
        val resultado = reasons(listOf(row(0, SafeDateCandidate(2023, 3, 1))), pageDateEpochDay = null)

        assertEquals(1, resultado.size)
        assertEquals(LedgerImplausibleDateReason.YEAR_MISMATCH, resultado.single().dateReason)
    }

    @Test
    fun `sin fecha de pagina una fecha vieja del mismo anio sigue siendo TOO_OLD`() {
        val resultado = reasons(listOf(row(0, candidate(hoy.minusDays(181)))), pageDateEpochDay = null)

        assertEquals(1, resultado.size)
        assertEquals(LedgerImplausibleDateReason.TOO_OLD, resultado.single().dateReason)
    }

    @Test
    fun `sin hoy el anio sigue comparandose y el motivo es YEAR_MISMATCH`() {
        val resultado = reasons(
            listOf(row(0, SafeDateCandidate(2023, 3, 1))),
            pageDateEpochDay = page,
            todayEpochDay = null,
        )

        assertEquals(1, resultado.size)
        assertEquals(LedgerImplausibleDateReason.YEAR_MISMATCH, resultado.single().dateReason)
    }

    @Test
    fun `un renglon con fecha plausible no trae motivo`() {
        assertTrue(reasons(listOf(row(0, SafeDateCandidate(2026, 9, 15))), pageDateEpochDay = page).isEmpty())
        // Y una objeción que no es de fecha no lleva motivo de fecha.
        val sinFecha = LedgerDeterministicObjection(
            lineIndex = 0,
            type = LedgerDeterministicObjectionType.UNUSUAL_CENTS,
        )
        assertNull(sinFecha.dateReason)
    }

    @Test
    fun `cada renglon trae su propio motivo en la misma pagina`() {
        val resultado = reasons(
            listOf(
                row(0, SafeDateCandidate(2023, 3, 1)),        // año distinto
                row(1, candidate(hoy.minusDays(200))),         // vieja, mismo año
                row(2, candidate(hoy.plusDays(2))),            // futura, mismo año
                row(3, SafeDateCandidate(2026, 9, 1)),         // plausible
            ),
            pageDateEpochDay = page,
        ).associateBy { it.lineIndex }

        assertEquals(LedgerImplausibleDateReason.YEAR_MISMATCH, resultado.getValue(0).dateReason)
        assertEquals(LedgerImplausibleDateReason.TOO_OLD, resultado.getValue(1).dateReason)
        assertEquals(LedgerImplausibleDateReason.FUTURE, resultado.getValue(2).dateReason)
        assertNull(resultado[3])
    }
}
