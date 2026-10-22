package com.cuentivo.app.ai.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict of the cross-check: what the app says about the page, and which rows carry a badge.
 *
 * The case this file exists for is the one that came out on a phone: a notebook of ONE customer,
 * twelve rows, and every single row saying "the Ledger did not see it". The Ledger had not said
 * that. It had said it could not certify the page (`BLOCKED`), and the app had turned "I could
 * not read the page" into twelve differences that did not exist.
 */
class MexalmCrosscheckVerdictTest {

    /** The page the phone read: twelve rows of one customer. */
    private fun twelveRowsMissingInLedger(): List<MexalmCrosscheckDisagreement> =
        (0 until 12).map { lineIndex ->
            MexalmCrosscheckDisagreement(
                lineIndex = lineIndex,
                field = MexalmCrosscheckDisagreementField.MISSING_IN_LEDGER,
                gemini = MexalmCrosscheckOperationSummary(
                    proposedKind = "SALE",
                    customerName = "Chelo",
                    amountCents = 10000L + lineIndex,
                ),
            )
        }

    @Test fun `el Ledger que no leyo la pagina produce un veredicto y ningun desacuerdo por renglon`() {
        // Asi es como llego el bloque: doce diferencias de "no lo vio" y el status que decia la
        // verdad, BLOCKED.
        val payload = MexalmCrosscheckPayload(
            agreed = false,
            disagreements = twelveRowsMissingInLedger(),
            ledgerStatus = "BLOCKED",
            latencyMs = 812L,
        )

        val verdict = MexalmCrosscheckVerdictCalculator.calculate(payload)

        assertEquals(MexalmCrosscheckOutcome.COULD_NOT_READ, verdict.outcome)
        assertEquals(MexalmCrosscheckUnreadReason.BLOCKED, verdict.unreadReason)
        // Ni una insignia por renglon: no le faltaron renglones al Ledger, no pudo leer la pagina.
        assertTrue(verdict.visibleDisagreements.isEmpty())
        assertEquals(0, verdict.disagreeingRowCount)
    }

    @Test fun `BLOCKED sin diferencias tambien es el veredicto unico`() {
        // El servidor nuevo ya no manda las doce diferencias: manda el status y nada mas.
        val payload = MexalmCrosscheckPayload(
            agreed = false,
            disagreements = emptyList(),
            ledgerStatus = "BLOCKED",
            latencyMs = 812L,
        )

        val verdict = MexalmCrosscheckVerdictCalculator.calculate(payload)

        assertEquals(MexalmCrosscheckOutcome.COULD_NOT_READ, verdict.outcome)
        assertEquals(MexalmCrosscheckUnreadReason.BLOCKED, verdict.unreadReason)
        assertTrue(verdict.visibleDisagreements.isEmpty())
    }

    @Test fun `REVIEW_REQUIRED es el mismo caso con otro motivo`() {
        val payload = MexalmCrosscheckPayload(
            agreed = false,
            disagreements = emptyList(),
            ledgerStatus = "REVIEW_REQUIRED",
            latencyMs = 812L,
        )

        val verdict = MexalmCrosscheckVerdictCalculator.calculate(payload)

        assertEquals(MexalmCrosscheckOutcome.COULD_NOT_READ, verdict.outcome)
        assertEquals(MexalmCrosscheckUnreadReason.REVIEW_REQUIRED, verdict.unreadReason)
    }

    @Test fun `con OK todo sigue igual y las diferencias se ven y se cuentan por renglon`() {
        val payload = MexalmCrosscheckPayload(
            agreed = false,
            disagreements = listOf(
                MexalmCrosscheckDisagreement(
                    lineIndex = 3,
                    field = MexalmCrosscheckDisagreementField.AMOUNT,
                    gemini = MexalmCrosscheckOperationSummary(amountCents = 10000L),
                    ledger = MexalmCrosscheckOperationSummary(amountCents = 9000L),
                ),
                MexalmCrosscheckDisagreement(
                    lineIndex = 3,
                    field = MexalmCrosscheckDisagreementField.CUSTOMER,
                    gemini = MexalmCrosscheckOperationSummary(customerName = "Chelo"),
                    ledger = MexalmCrosscheckOperationSummary(customerName = "Beto"),
                ),
            ),
            ledgerStatus = "OK",
            latencyMs = 812L,
        )

        val verdict = MexalmCrosscheckVerdictCalculator.calculate(payload)

        assertEquals(MexalmCrosscheckOutcome.DISAGREEMENTS, verdict.outcome)
        assertEquals(null, verdict.unreadReason)
        // Las dos diferencias se ven: dos insignias en un renglon, y un renglon en el conteo.
        assertEquals(2, verdict.visibleDisagreements.size)
        assertEquals(1, verdict.disagreeingRowCount)
    }

    @Test fun `el Ledger de acuerdo sigue siendo el Ledger de acuerdo`() {
        val payload = MexalmCrosscheckPayload(
            agreed = true,
            disagreements = emptyList(),
            ledgerStatus = "OK",
            latencyMs = 812L,
        )

        val verdict = MexalmCrosscheckVerdictCalculator.calculate(payload)

        assertEquals(MexalmCrosscheckOutcome.AGREED, verdict.outcome)
        assertTrue(verdict.visibleDisagreements.isEmpty())
    }

    @Test fun `sin veredicto y sin diferencias NO es que esten de acuerdo`() {
        // agreed en falso sin una sola diferencia: el Ledger no dio veredicto. Decir que estan de
        // acuerdo seria mentir.
        val payload = MexalmCrosscheckPayload(
            agreed = false,
            disagreements = emptyList(),
            ledgerStatus = "OK",
            latencyMs = 812L,
        )

        val verdict = MexalmCrosscheckVerdictCalculator.calculate(payload)

        assertEquals(MexalmCrosscheckOutcome.NO_VERDICT, verdict.outcome)
    }

    @Test fun `una diferencia de valor se ve aunque el Ledger no haya leido la pagina`() {
        // El Ledger no leyo la pagina, pero lo que SI leyo se compara: si de un renglon dice otra
        // cosa, eso si es una diferencia de ese renglon.
        val payload = MexalmCrosscheckPayload(
            agreed = false,
            disagreements = listOf(
                MexalmCrosscheckDisagreement(
                    lineIndex = 1,
                    field = MexalmCrosscheckDisagreementField.AMOUNT,
                    gemini = MexalmCrosscheckOperationSummary(amountCents = 10000L),
                    ledger = MexalmCrosscheckOperationSummary(amountCents = 9000L),
                ),
            ),
            ledgerStatus = "REVIEW_REQUIRED",
            latencyMs = 812L,
        )

        val verdict = MexalmCrosscheckVerdictCalculator.calculate(payload)

        assertEquals(MexalmCrosscheckOutcome.COULD_NOT_READ, verdict.outcome)
        assertEquals(1, verdict.visibleDisagreements.size)
        assertEquals(1, verdict.disagreeingRowCount)
    }

    @Test fun `el status crudo del Ledger viaja en el veredicto`() {
        val payload = MexalmCrosscheckPayload(
            agreed = false,
            disagreements = emptyList(),
            ledgerStatus = "BLOCKED",
            latencyMs = 812L,
        )

        val verdict = MexalmCrosscheckVerdictCalculator.calculate(payload)

        // Quien tenga que diagnosticar lo ve tal cual lo dijo el Ledger.
        assertEquals("BLOCKED", verdict.ledgerStatus)
    }
}
