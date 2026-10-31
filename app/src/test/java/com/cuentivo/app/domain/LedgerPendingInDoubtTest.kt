package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.CustomerReviewGroup
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El "por revisar" que reemplaza al "Saldo: $0.00 → $0.00" de un renglón en duda.
 *
 * El defecto real: una venta de $999 quedaba bloqueada por la objeción de fecha, no entraba al
 * saldo resultante, y la tarjeta decía "Saldo: $0.00 → $0.00", como si no se fuera a anotar nada.
 */
class LedgerPendingInDoubtTest {

    private fun charge(lineIndex: Int, cents: String) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = cents,
    )

    private fun group(all: List<LedgerOperation>, executable: List<LedgerOperation>) = CustomerReviewGroup(
        customerName = "Rosa",
        normalizedCustomerName = "rosa",
        isNew = true,
        previousBalanceCents = 0L,
        resultingBalanceCents = 0L,
        operations = all,
        executableOperations = executable,
        financialDiagnostics = emptyList(),
    )

    @Test
    fun `suma solo los cargos que no son ejecutables`() {
        val pending = charge(1, "99900")
        val g = group(all = listOf(charge(0, "15000"), pending), executable = listOf(charge(0, "15000")))
        assertEquals(99900L, LedgerPendingInDoubt.chargeCents(g))
    }

    @Test
    fun `un abono en duda no cuenta como pendiente por revisar`() {
        val payment = charge(0, "5000").copy(proposedKind = ProposedLedgerKind.PAYMENT)
        val g = group(all = listOf(payment), executable = emptyList())
        assertEquals(0L, LedgerPendingInDoubt.chargeCents(g))
    }

    @Test
    fun `una deuda anterior en duda si cuenta`() {
        val prior = charge(0, "15000").copy(proposedKind = ProposedLedgerKind.PRIOR_DEBT)
        val g = group(all = listOf(prior), executable = emptyList())
        assertEquals(15000L, LedgerPendingInDoubt.chargeCents(g))
    }

    @Test
    fun `sin renglones en duda no hay nada que sumar`() {
        val g = group(all = listOf(charge(0, "15000")), executable = listOf(charge(0, "15000")))
        assertEquals(0L, LedgerPendingInDoubt.chargeCents(g))
    }
}
