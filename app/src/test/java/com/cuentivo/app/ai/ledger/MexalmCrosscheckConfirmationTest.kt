package com.cuentivo.app.ai.ledger

import com.cuentivo.app.domain.LedgerImportSummaryCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El acuerdo del Ledger de MexaLM resuelve la duda de LECTURA de un renglón, y sólo esa.
 *
 * El caso que motivó esto: una libreta de doce renglones, el Ledger de acuerdo en los doce, y la
 * app diciendo "0 listos para registrar · 12 por revisar". El dueño tuvo que revisar los doce a
 * mano, uno por uno, contra la foto.
 *
 * Y lo que este archivo protege es lo que NO se debe resolver: el año distinto al de la hoja, una
 * fecha rara, un cliente nuevo, un posible duplicado. Nada de eso lo verifica el Ledger, así que
 * nada de eso lo resuelve.
 */
class MexalmCrosscheckConfirmationTest {

    companion object {
        /** El id de la petición, tal como lo devuelve el servidor. */
        const val REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000"
    }

    private fun doubtfulRow(
        lineIndex: Int = 0,
        uncertain: List<UncertainLedgerField> = listOf(UncertainLedgerField.AMOUNT),
        unreadable: List<UnreadableLedgerField> = emptyList(),
        year: Int = 2026,
    ): LedgerOperation = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Chelo",
        amountCents = "25000",
        date = SafeDateCandidate(year = year, month = 3, day = 10),
        uncertainFields = uncertain,
        unreadableFields = unreadable,
    )

    /** El bloque del servidor: el Ledger de acuerdo y estos renglones confirmados. */
    private fun agreeingBlock(
        confirmed: Set<Int> = setOf(0),
        status: String = "OK",
        disagreements: List<MexalmCrosscheckDisagreement> = emptyList(),
    ): MexalmCrosscheckPayload = MexalmCrosscheckPayload(
        agreed = disagreements.isEmpty(),
        disagreements = disagreements,
        ledgerStatus = status,
        latencyMs = 812L,
        confirmedLineIndices = confirmed,
    )

    @Test
    fun `un renglon con acuerdo y solo duda de lectura queda listo`() {
        val row = doubtfulRow()
        assertFalse(isRegisterableLedgerOperation(row))

        val resolved = MexalmCrosscheckConfirmation.resolveReadingDoubts(row, agreeingBlock())

        // La duda de lectura desaparece: el renglón ya no pide revisión.
        assertTrue(resolved.uncertainFields.isEmpty())
        assertTrue(isRegisterableLedgerOperation(resolved))
    }

    @Test
    fun `la duda de cliente y la de tipo tambien se resuelven`() {
        val row = doubtfulRow(
            uncertain = listOf(UncertainLedgerField.CUSTOMER, UncertainLedgerField.KIND),
        )

        val resolved = MexalmCrosscheckConfirmation.resolveReadingDoubts(row, agreeingBlock())

        assertTrue(resolved.uncertainFields.isEmpty())
        assertTrue(isRegisterableLedgerOperation(resolved))
    }

    @Test
    fun `el anio distinto sigue pidiendo confirmacion`() {
        // La duda de FECHA no es de lectura: el Ledger no compara fechas, así que no puede
        // resolverla. El renglón sigue pidiendo revisión.
        val row = doubtfulRow(
            uncertain = listOf(UncertainLedgerField.AMOUNT, UncertainLedgerField.DATE),
        )

        val resolved = MexalmCrosscheckConfirmation.resolveReadingDoubts(row, agreeingBlock())

        assertTrue(resolved.uncertainFields.contains(UncertainLedgerField.DATE))
        assertFalse(resolved.uncertainFields.contains(UncertainLedgerField.AMOUNT))
        assertFalse(isRegisterableLedgerOperation(resolved))
    }

    @Test
    fun `una fecha ilegible tampoco se resuelve`() {
        val row = doubtfulRow(
            uncertain = listOf(UncertainLedgerField.AMOUNT),
            unreadable = listOf(UnreadableLedgerField.DATE),
        )

        val resolved = MexalmCrosscheckConfirmation.resolveReadingDoubts(row, agreeingBlock())

        assertTrue(resolved.unreadableFields.contains(UnreadableLedgerField.DATE))
        assertFalse(isRegisterableLedgerOperation(resolved))
    }

    @Test
    fun `un desacuerdo sigue en duda`() {
        val row = doubtfulRow()
        val block = agreeingBlock(
            confirmed = emptySet(),
            disagreements = listOf(
                MexalmCrosscheckDisagreement(
                    lineIndex = 0,
                    field = MexalmCrosscheckDisagreementField.AMOUNT,
                    gemini = MexalmCrosscheckOperationSummary(amountCents = 25000L),
                    ledger = MexalmCrosscheckOperationSummary(amountCents = 24000L),
                ),
            ),
        )

        val resolved = MexalmCrosscheckConfirmation.resolveReadingDoubts(row, block)

        assertTrue(resolved.uncertainFields.contains(UncertainLedgerField.AMOUNT))
        assertFalse(isRegisterableLedgerOperation(resolved))
    }

    @Test
    fun `BLOCKED se comporta como hoy`() {
        val row = doubtfulRow()
        val block = agreeingBlock(confirmed = emptySet(), status = "BLOCKED")

        val resolved = MexalmCrosscheckConfirmation.resolveReadingDoubts(row, block)

        // El Ledger no leyó la página: no hay veredicto, y la duda se queda donde estaba.
        assertEquals(row, resolved)
        assertFalse(isRegisterableLedgerOperation(resolved))
    }

    @Test
    fun `con la bandera apagada nada cambia`() {
        // Sin bloque —la respuesta normal de un servidor con la bandera apagada— el renglón es
        // exactamente el de antes.
        val row = doubtfulRow()

        val resolved = MexalmCrosscheckConfirmation.resolveReadingDoubts(row, null)

        assertEquals(row, resolved)
        assertFalse(isRegisterableLedgerOperation(resolved))
    }

    @Test
    fun `un Ledger que no tuvo opinion no resuelve nada aunque no haya desacuerdos`() {
        // agreed=true sin lista de confirmados: el Ledger no discrepó de nada porque no dijo nada.
        val row = doubtfulRow()
        val block = MexalmCrosscheckPayload(
            agreed = true,
            disagreements = emptyList(),
            ledgerStatus = "OK",
            latencyMs = 812L,
        )

        val resolved = MexalmCrosscheckConfirmation.resolveReadingDoubts(row, block)

        assertEquals(row, resolved)
        assertFalse(isRegisterableLedgerOperation(resolved))
    }

    @Test
    fun `un renglon que el Ledger no confirmo no se toca`() {
        val row = doubtfulRow(lineIndex = 7)

        val resolved = MexalmCrosscheckConfirmation.resolveReadingDoubts(row, agreeingBlock(confirmed = setOf(0)))

        assertEquals(row, resolved)
    }

    @Test
    fun `el monto que se registra es el de la lectura, no el del Ledger`() {
        val row = doubtfulRow()

        val resolved = MexalmCrosscheckConfirmation.resolveReadingDoubts(row, agreeingBlock())

        // Resolver una duda no reescribe lo que se anota.
        assertEquals("25000", resolved.amountCents)
        assertEquals("Chelo", resolved.customerName)
        assertEquals(ProposedLedgerKind.SALE, resolved.proposedKind)
    }

    @Test
    fun `los doce renglones de la libreta quedan listos y el resumen lo dice`() {
        // El caso del dueño, de punta a punta: doce renglones dudosos, el Ledger de acuerdo en
        // los doce, y el resumen contando doce listos para registrar.
        val rows = (0 until 12).map { lineIndex ->
            doubtfulRow(lineIndex = lineIndex, uncertain = listOf(UncertainLedgerField.AMOUNT))
        }
        val block = agreeingBlock(confirmed = (0 until 12).toSet())

        val review = LedgerReviewEngine.build(
            operations = rows,
            mexalmCrosscheck = block,
            pageDateEpochDay = 20_000L,
            todayEpochDay = 20_100L,
        )
        val summary = LedgerImportSummaryCalculator.calculate(review, emptyList())

        assertEquals(12, summary.readyCount)
        assertEquals(0, summary.pendingReviewCount)
    }

    @Test
    fun `sin el bloque el resumen es el de siempre`() {
        val rows = (0 until 12).map { lineIndex ->
            doubtfulRow(lineIndex = lineIndex, uncertain = listOf(UncertainLedgerField.AMOUNT))
        }

        val review = LedgerReviewEngine.build(
            operations = rows,
            pageDateEpochDay = 20_000L,
            todayEpochDay = 20_100L,
        )
        val summary = LedgerImportSummaryCalculator.calculate(review, emptyList())

        // Con la bandera apagada, los doce siguen pidiendo revisión: nada cambia.
        assertEquals(0, summary.readyCount)
        assertEquals(12, summary.pendingReviewCount)
    }

    @Test
    fun `un renglon confirmado sigue con sus objeciones`() {
        // La objeción de una fecha con año equivocado la calcula la app con los datos del
        // negocio: el Ledger no la resuelve y la app no la suelta.
        val row = doubtfulRow(year = 2023)
        val review = LedgerReviewEngine.build(
            operations = listOf(row),
            mexalmCrosscheck = agreeingBlock(),
            pageDateEpochDay = 20_000L,
            todayEpochDay = 20_100L,
        )
        val objections = com.cuentivo.app.domain.LedgerDeterministicObjections.evaluate(
            operations = review.operations,
            existingCustomerCandidates = emptyList(),
            catalogProducts = emptyList(),
            pageDateEpochDay = review.pageDateEpochDay,
            todayEpochDay = review.todayEpochDay,
        )

        assertTrue(objections.any { it.type == com.cuentivo.app.domain.LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE })
        // Y la objeción sigue frenando el renglón: el resumen no lo cuenta como listo.
        val summary = LedgerImportSummaryCalculator.calculate(review, objections)
        assertEquals(0, summary.readyCount)
    }

    @Test
    fun `el veredicto cuenta los renglones confirmados`() {
        val verdict = MexalmCrosscheckVerdictCalculator.calculate(agreeingBlock(confirmed = setOf(0, 1, 2)))

        assertEquals(3, verdict.confirmedRowCount)
    }

    @Test
    fun `sin veredicto no hay renglones confirmados`() {
        val verdict = MexalmCrosscheckVerdictCalculator.calculate(
            agreeingBlock(confirmed = setOf(0, 1, 2), status = "BLOCKED"),
        )

        assertEquals(0, verdict.confirmedRowCount)
    }

    /**
     * De punta a punta, con la respuesta TAL COMO la manda el servidor: la libreta de doce
     * renglones dudosos, el bloque con los doce confirmados, y el resumen de la app.
     *
     * Esta es la prueba del caso del dueño: si el bloque no llega bien, o si la regla no se
     * aplica donde tiene que aplicarse, el resumen sigue diciendo "0 listos para registrar" y
     * el dueño tiene que revisar los doce a mano otra vez.
     */
    @Test
    fun `de punta a punta, la respuesta del servidor deja los doce renglones listos`() {
        val operations = (0 until 12).map { lineIndex ->
            mapOf(
                "lineIndex" to lineIndex,
                "proposedKind" to "SALE",
                "customerName" to "Chelo",
                "amountCents" to (10000 + lineIndex).toString(),
                // La duda de lectura del modelo: el monto.
                "uncertainFields" to listOf("AMOUNT"),
                "unreadableFields" to emptyList<String>(),
                "date" to mapOf("year" to 2026, "month" to 3, "day" to 10),
            )
        }
        val wire: Map<String, Any?> = mapOf(
            "contractVersion" to 5,
            "requestId" to REQUEST_ID,
            "status" to "AI_PROPOSAL",
            "operations" to operations,
            "discards" to emptyList<Any>(),
            "unreadTextRemaining" to false,
            "pageIncomplete" to false,
            "mexalmCrosscheck" to mapOf(
                "agreed" to true,
                "disagreements" to emptyList<Any>(),
                "ledgerStatus" to "OK",
                "latencyMs" to 812,
                "confirmedLineIndexes" to (0 until 12).toList(),
            ),
        )

        val parsed = parseLedgerExtractionResponse(wire, REQUEST_ID)
        assertTrue(parsed is LedgerExtractionCallResult.Extracted)
        val payload = (parsed as LedgerExtractionCallResult.Extracted).extraction

        val review = LedgerReviewEngine.build(
            operations = payload.operations,
            mexalmCrosscheck = payload.mexalmCrosscheck,
            pageDateEpochDay = 20_000L,
            todayEpochDay = 20_100L,
        )
        val summary = LedgerImportSummaryCalculator.calculate(review, emptyList())

        // "12 listos para registrar - 0 por revisar": el problema del dueño, resuelto.
        assertEquals(12, summary.readyCount)
        assertEquals(0, summary.pendingReviewCount)
    }
}
