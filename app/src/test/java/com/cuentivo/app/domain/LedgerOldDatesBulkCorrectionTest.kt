package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Confirmar todos" para las fechas simplemente viejas (v46).
 *
 * Con deudas de hace meses, el año ya está bien y la objeción es de antigüedad: confirmar renglón por
 * renglón son doce diálogos por algo que no es un error. La tarjeta sólo aparece con dos o más, y
 * **sólo** toca a los viejos: los de año distinto tienen su propio arreglo y no pueden colarse.
 */
class LedgerOldDatesBulkCorrectionTest {

    private fun objection(
        lineIndex: Int,
        reason: LedgerImplausibleDateReason?,
        type: LedgerDeterministicObjectionType = LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE,
    ) = LedgerDeterministicObjection(lineIndex = lineIndex, type = type, dateReason = reason)

    @Test
    fun `sin fechas viejas no hay tarjeta`() {
        assertTrue(LedgerOldDatesBulkCorrection.lineIndices(emptyList()).isEmpty())
    }

    @Test
    fun `una sola fecha vieja no arma tarjeta - el dialogo del renglon ya la confirma`() {
        assertTrue(
            LedgerOldDatesBulkCorrection.lineIndices(
                listOf(objection(3, LedgerImplausibleDateReason.TOO_OLD)),
            ).isEmpty(),
        )
    }

    @Test
    fun `dos o mas fechas viejas arman la tarjeta, en orden`() {
        assertEquals(
            listOf(1, 4, 7),
            LedgerOldDatesBulkCorrection.lineIndices(
                listOf(
                    objection(7, LedgerImplausibleDateReason.TOO_OLD),
                    objection(1, LedgerImplausibleDateReason.TOO_OLD),
                    objection(4, LedgerImplausibleDateReason.TOO_OLD),
                ),
            ),
        )
    }

    @Test
    fun `no se cuelan las de anio distinto ni las futuras`() {
        assertEquals(
            listOf(0, 2),
            LedgerOldDatesBulkCorrection.lineIndices(
                listOf(
                    objection(0, LedgerImplausibleDateReason.TOO_OLD),
                    objection(1, LedgerImplausibleDateReason.YEAR_MISMATCH),
                    objection(2, LedgerImplausibleDateReason.TOO_OLD),
                    objection(3, LedgerImplausibleDateReason.FUTURE),
                ),
            ),
        )
    }

    @Test
    fun `otras objeciones no entran aunque traigan el tipo de fecha por error`() {
        assertEquals(
            listOf(5, 6),
            LedgerOldDatesBulkCorrection.lineIndices(
                listOf(
                    objection(0, null, LedgerDeterministicObjectionType.PRICE_MISMATCH_SUSPECT),
                    objection(1, null, LedgerDeterministicObjectionType.UNUSUAL_CENTS),
                    objection(5, LedgerImplausibleDateReason.TOO_OLD),
                    objection(6, LedgerImplausibleDateReason.TOO_OLD),
                ),
            ),
        )
    }

    @Test
    fun `el mismo renglon dos veces cuenta una sola`() {
        // Dos motivos para el mismo índice no son dos renglones que confirmar.
        assertTrue(
            LedgerOldDatesBulkCorrection.lineIndices(
                listOf(
                    objection(2, LedgerImplausibleDateReason.TOO_OLD),
                    objection(2, LedgerImplausibleDateReason.TOO_OLD),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `confirmar todos solo afecta a los viejos, y cambiar la fecha despues vuelve a objetar`() {
        val page = LocalDate.of(2026, 9, 10).toEpochDay()
        val hoy = LocalDate.of(2026, 9, 22).toEpochDay()
        val rows = listOf(
            operation(0, SafeDateCandidate(2026, 3, 1)),   // vieja, mismo año
            operation(1, SafeDateCandidate(2026, 3, 2)),   // vieja, mismo año
            operation(2, SafeDateCandidate(2023, 3, 1)),   // año distinto
        )
        val objections = LedgerDeterministicObjections.evaluate(
            operations = rows,
            existingCustomerCandidates = emptyList(),
            catalogProducts = emptyList(),
            pageDateEpochDay = page,
            todayEpochDay = hoy,
        )
        val oldIndices = LedgerOldDatesBulkCorrection.lineIndices(objections)
        assertEquals(listOf(0, 1), oldIndices)

        // "Confirmar todos" es el mismo `confirmObjection` de siempre, aplicado a esos índices.
        var afterBulk = rows
        oldIndices.forEach { lineIndex ->
            afterBulk = LedgerReviewEdits.confirmObjection(afterBulk, lineIndex)
        }

        assertTrue("los viejos quedan confirmados", LedgerObjectionConfirmation.isConfirmed(afterBulk[0]))
        assertTrue(LedgerObjectionConfirmation.isConfirmed(afterBulk[1]))
        assertFalse(
            "el de año distinto no se toca: no era una fecha vieja",
            LedgerObjectionConfirmation.isConfirmed(afterBulk[2]),
        )

        // Y si después corrigen la fecha de un renglón confirmado, la huella cambia y vuelve a objetar.
        val edited = LedgerReviewEdits.applyDate(
            afterBulk,
            lineIndex = 0,
            date = SafeDateCandidate(2024, 3, 1),
            todayEpochDay = hoy,
        )
        assertFalse(
            "cambiar la fecha tras confirmar tiene que volver a pedir confirmación",
            LedgerObjectionConfirmation.isConfirmed(edited[0]),
        )
    }

    private fun operation(lineIndex: Int, date: SafeDateCandidate) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = "25000",
        rawText = "Rosa 250",
        date = date,
    )
}
