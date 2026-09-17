package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.domain.LedgerReviewSchema
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Una revisión restaurada de un guardado hecho por una versión anterior **no se puede registrar**.
 *
 * Esa versión calculaba la clave de idempotencia con el contenido de la página, así que no hay forma
 * de saber si lo que hay en el libro es esta página o no. Registrar a ciegas podría escribir un cargo
 * dos veces; dejar de registrar cuesta una captura. La asimetría decide.
 */
class LedgerLegacyRestoreGateTest {

    private val sessionId = "sesion-1"
    private val businessId = "biz-1"
    private val pageDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay()
    private val todayEpochDay = LocalDate.of(2026, 9, 16).toEpochDay()

    private fun cleanRow() = LedgerOperation(
        lineIndex = 0,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = "25000",
        rawText = "1010 Cuaderno 250 Rosa",
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    private fun saved(reviewSchemaVersion: Int?) = LedgerReviewSaver.SavedLedgerReview(
        sessionId = sessionId,
        businessId = businessId,
        operations = listOf(cleanRow()),
        originalOperations = listOf(cleanRow()),
        discards = emptyList(),
        unreadTextRemaining = false,
        pageIncomplete = false,
        pageDateEpochDay = pageDateEpochDay,
        todayEpochDay = todayEpochDay,
        detectedPageDate = null,
        reviewSchemaVersion = reviewSchemaVersion,
    )

    private fun restore(reviewSchemaVersion: Int?): AiDebugLabModel =
        AiDebugLabMachine.onRestoreLedgerReview(
            model = AiDebugLabModel(),
            sessionId = sessionId,
            businessId = businessId,
            saved = saved(reviewSchemaVersion),
            existingCustomerBalances = emptyMap(),
            customerCandidates = emptyList(),
            productCandidates = emptyList(),
            registeredOperations = emptyList(),
        )

    @Test
    fun `una_revision_restaurada_sin_marca_queda_bloqueada_y_no_confirma`() {
        val model = restore(reviewSchemaVersion = null)
        val state = model.state as AiDebugLabState.LedgerReviewing

        assertTrue(
            "una revisión sin marca tiene que quedar marcada como no registrable",
            state.isLegacySaveWithoutSessionKey,
        )
        // Y la máquina lo hace cumplir, no sólo la pantalla: esconder un botón no es un control.
        assertEquals(
            AiLedgerConfirmOutcome.Ignored,
            AiDebugLabMachine.onConfirmLedger(model, sessionId, businessId),
        )
    }

    @Test
    fun `una_revision_restaurada_con_la_marca_actual_si_confirma`() {
        val model = restore(reviewSchemaVersion = LedgerReviewSchema.CURRENT)
        val state = model.state as AiDebugLabState.LedgerReviewing

        assertFalse(state.isLegacySaveWithoutSessionKey)
        assertTrue(
            "la revisión está completa, así que confirmar tiene que arrancar",
            AiDebugLabMachine.onConfirmLedger(model, sessionId, businessId) is AiLedgerConfirmOutcome.Started,
        )
    }

    @Test
    fun `una_revision_restaurada_de_una_version_mas_vieja_queda_bloqueada`() {
        val model = restore(reviewSchemaVersion = LedgerReviewSchema.CURRENT - 1)
        val state = model.state as AiDebugLabState.LedgerReviewing

        assertTrue(state.isLegacySaveWithoutSessionKey)
    }

    @Test
    fun `una_revision_restaurada_de_la_version_2_no_se_puede_registrar`() {
        // La versión 2 no guardaba `pageType`, `pageTotals` ni `marginNote`: restaurarla perdería el
        // tipo de hoja y los subtotales declarados, y con ellos la duda que justifican.
        val model = restore(reviewSchemaVersion = 2)
        val state = model.state as AiDebugLabState.LedgerReviewing

        assertTrue(state.isLegacySaveWithoutSessionKey)
        assertEquals(
            AiLedgerConfirmOutcome.Ignored,
            AiDebugLabMachine.onConfirmLedger(model, sessionId, businessId),
        )
    }
}
