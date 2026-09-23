package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3 · Una confirmación no puede sobrevivir a un cambio de fecha o de tipo (v44).
 *
 * La huella incluía cliente, monto y descripción, pero no fecha ni tipo. Tras confirmar, cambiar sólo
 * la fecha (a otro año equivocado) o el tipo mantenía la confirmación y el renglón pasaba sin revisar.
 * Aquí se fija que cualquier cambio en esas dos señales anula la confirmación.
 */
class LedgerObjectionConfirmationTest {

    private val today = LocalDate.of(2026, 9, 22).toEpochDay()

    private fun row(
        date: SafeDateCandidate? = SafeDateCandidate(2026, 8, 14),
        kind: ProposedLedgerKind? = ProposedLedgerKind.SALE,
    ) = LedgerOperation(
        lineIndex = 0,
        proposedKind = kind,
        customerName = "Laura",
        amountCents = "99900",
        date = date,
    )

    @Test
    fun `confirmar y no cambiar nada sigue confirmado`() {
        val confirmed = LedgerObjectionConfirmation.confirming(row())
        assertTrue(LedgerObjectionConfirmation.isConfirmed(confirmed))
    }

    @Test
    fun `confirmar y cambiar la fecha vuelve a pedir confirmacion`() {
        val confirmed = LedgerObjectionConfirmation.confirming(row(date = SafeDateCandidate(2026, 8, 14)))
        // La persona corrige sólo la fecha a otro año equivocado.
        val edited = LedgerReviewEdits.applyDate(
            listOf(confirmed),
            lineIndex = 0,
            date = SafeDateCandidate(2023, 3, 1),
            todayEpochDay = today,
        ).single()
        assertFalse(
            "un cambio de fecha tiene que anular la confirmación",
            LedgerObjectionConfirmation.isConfirmed(edited),
        )
    }

    @Test
    fun `confirmar y cambiar el tipo vuelve a pedir confirmacion`() {
        val confirmed = LedgerObjectionConfirmation.confirming(row(kind = ProposedLedgerKind.SALE))
        val edited = LedgerReviewEdits.applyKind(listOf(confirmed), lineIndex = 0, kind = ProposedLedgerKind.PAYMENT).single()
        assertFalse(
            "un cambio de tipo tiene que anular la confirmación",
            LedgerObjectionConfirmation.isConfirmed(edited),
        )
    }

    @Test
    fun `confirmar y cambiar el monto sigue anulando la confirmacion`() {
        // Lo que ya funcionaba, para que no se pierda al añadir fecha y tipo.
        val confirmed = LedgerObjectionConfirmation.confirming(row())
        val edited = LedgerReviewEdits.applyAmount(listOf(confirmed), lineIndex = 0, amountCents = "30000").single()
        assertFalse(LedgerObjectionConfirmation.isConfirmed(edited))
    }

    @Test
    fun `una huella vieja sin fecha ni tipo no vale para el renglon actual`() {
        // Un borrador guardado con la huella vieja (sólo cliente/monto/descripción) no coincide con la
        // nueva, así que al restaurar el renglón vuelve a pedir confirmación. No rompe el borrador.
        val op = row()
        val oldFingerprint = listOf(
            "${op.customerId.orEmpty().length}:${op.customerId.orEmpty()}",
            "${CustomerNameNormalizer.key(op.customerName.orEmpty()).length}:${CustomerNameNormalizer.key(op.customerName.orEmpty())}",
            "${op.amountCents.orEmpty().length}:${op.amountCents.orEmpty()}",
            "${CustomerNameNormalizer.key(op.description.orEmpty()).length}:${CustomerNameNormalizer.key(op.description.orEmpty())}",
        ).joinToString(":")
        val restored = op.copy(isObjectionConfirmed = true, objectionConfirmedFingerprint = oldFingerprint)
        assertFalse(
            "una huella vieja no puede dar por confirmado un renglón con la huella nueva",
            LedgerObjectionConfirmation.isConfirmed(restored),
        )
    }
}
