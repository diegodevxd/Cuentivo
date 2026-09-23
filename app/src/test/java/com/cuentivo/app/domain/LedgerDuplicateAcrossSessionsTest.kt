package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.data.local.ExpectedLedgerBatchResult
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C · La página de Diego registrada dos veces (v44): reproducción con la secuencia exacta —
 * registrar la misma página dos veces con `importSessionId` distintos — y qué pasa en cada capa.
 *
 * Lo que esta prueba demuestra, con el código que producción ejecuta:
 *
 * 1. Dos sesiones distintas escriben el dinero dos veces. La clave de lote sale de la sesión
 *    (`AiLedgerBatchPlanner.batchKeyFor`), a propósito: dos fotos de la misma libreta son dos
 *    páginas ([LedgerSessionKeyIdempotencyTest]). La idempotencia por sesión NO puede frenar
 *    esto — sólo lo reintentaría dentro de la misma sesión.
 * 2. El detector SÍ casa cuando la segunda lectura trae los mismos datos que escribió la
 *    primera: cliente, monto, ±7 días y tipo coinciden, y la consulta por rango de fechas
 *    (`FinancialDao.getRegisteredOperationsInRange`, ventana ±14 días alrededor de las fechas
 *    de los renglones) devuelve las ventas de 2023 si la segunda lectura también leyó 2023.
 * 3. El aviso de duplicado SÍ entra en los renglones bloqueados (v44, decisión de Diego
 *    2026-09-22): el renglón queda sin resolver hasta que la persona confirme "Sí, es otra
 *    venta", igual que con una objeción determinista. Antes sólo avisaba y la página se
 *    registraba igual; ahora la segunda sesión no escribe hasta esa confirmación, y una compra
 *    repetida legítima sigue siendo registrable después de confirmarla.
 *
 * Los datos de la prueba son recientes (2026) para que la objeción de fecha imposible de v44
 * no se cruce: el aislamiento del duplicado es el objetivo de esta clase.
 */
class LedgerDuplicateAcrossSessionsTest {

    private val businessId = "biz-1"
    private val userId = "user-1"
    private val todayEpochDay = LocalDate.of(2026, 9, 16).toEpochDay()
    private val pageDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay()
    private val nowEpochMillis = 1_700_000_000_000L

    /** La misma página que se fotografía dos veces: dos renglones, montos y fechas idénticos. */
    private fun page() = listOf(
        ledgerRow(0, "Laura", "99900"),
        ledgerRow(1, "Estef", "15000"),
    )

    private fun ledgerRow(lineIndex: Int, name: String, cents: String) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = cents,
        rawText = "$name $cents",
        date = SafeDateCandidate(2026, 9, 12),
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    private val writtenPlans = mutableListOf<PlannedLedgerBatch>()

    private suspend fun confirm(
        operations: List<LedgerOperation>,
        importSessionId: String,
        existingCustomers: Map<String, com.cuentivo.app.data.local.CustomerEntity> = emptyMap(),
    ): LedgerBatchConfirmation = LedgerBatchCoordinator.confirm(
        businessId = businessId,
        userId = userId,
        importSessionId = importSessionId,
        operations = operations,
        blockedLineIndices = emptySet(),
        modelSaysTextMayBeMissing = false,
        isPageCompletenessConfirmed = false,
        pageDateEpochDay = pageDateEpochDay,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        alreadyRecorded = { key -> writtenPlans.any { it.batchIdempotencyKey == key } },
        loadCustomersByNormalizedName = { existingCustomers },
        loadOutstandingByCustomerId = { emptyMap() },
        write = { plan ->
            writtenPlans += plan
            ExpectedLedgerBatchResult.Recorded(plan.batchIdempotencyKey, plan.executableOperationsCount)
        },
    )

    /** Lo que `getRegisteredOperationsInRange` devuelve tras la primera escritura. */
    private fun projectionsFromFirstWrite(): List<RegisteredOperationProjection> {
        val plan = writtenPlans.first()
        return plan.sales.map { sale ->
            RegisteredOperationProjection(
                id = sale.id,
                customerId = sale.customerId,
                customerName = plan.newCustomers.first { it.id == sale.customerId }.name,
                amountCents = sale.totalCents,
                effectiveDateEpochDay = sale.saleDateEpochDay,
                kind = ProposedLedgerKind.SALE,
            )
        }
    }

    @Test
    fun `registrar la misma pagina dos veces con importSessionId distintos escribe el dinero dos veces`() {
        val operations = page()

        val first = kotlinx.coroutines.runBlocking { confirm(operations, "sesion-1") }
        assertEquals(LedgerBatchConfirmation.Recorded(2), first)

        // La persona no ve el resultado (sin refresco) y fotografía otra vez: sesión nueva.
        val second = kotlinx.coroutines.runBlocking { confirm(operations, "sesion-2") }
        assertEquals(
            "la clave de lote depende de la sesión: la segunda foto vuelve a escribir",
            LedgerBatchConfirmation.Recorded(2),
            second,
        )

        assertEquals(2, writtenPlans.size)
        assertNotEquals(
            "dos sesiones producen dos claves de lote distintas",
            writtenPlans[0].batchIdempotencyKey,
            writtenPlans[1].batchIdempotencyKey,
        )
        assertEquals(
            "Laura queda con 2 x 99900: exactamente el $1,998 que se vio en el teléfono",
            listOf(99_900L, 15_000L),
            writtenPlans[1].sales.map { it.totalCents },
        )
    }

    @Test
    fun `el detector de duplicados caza la segunda lectura cuando los datos son los mismos`() {
        kotlinx.coroutines.runBlocking { confirm(page(), "sesion-1") }
        val registered = projectionsFromFirstWrite()

        // La segunda extracción lee la misma foto: mismos renglones que escribió la primera.
        val matches = LedgerDuplicateDetector.evaluate(
            operations = page(),
            existingRegistered = registered,
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
        )

        assertEquals(
            "con datos idénticos el detector casa en todos los renglones (el badge SÍ se muestra)",
            setOf(0, 1),
            matches.keys,
        )
        assertEquals(
            "apunta a la venta ya escrita por la primera sesión",
            writtenPlans.first().sales.first().id,
            matches.getValue(0).matchedRegisteredId,
        )
    }

    @Test
    fun `el duplicado frena el segundo registro hasta confirmar y lo deja pasar despues`() {
        kotlinx.coroutines.runBlocking { confirm(page(), "sesion-1") }
        val registered = projectionsFromFirstWrite()
        val secondRead = page()

        val matches = LedgerDuplicateDetector.evaluate(
            operations = secondRead,
            existingRegistered = registered,
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
        )
        assertTrue("para que el caso valga, el detector tiene que estar avisando", matches.isNotEmpty())

        // v44: el duplicado entra en los bloqueados, igual que una objeción determinista. La
        // pantalla y la puerta de escritura usan el mismo objeto puro, así que no pueden discrepar.
        val blocked = LedgerDuplicateBlocking.blockedLineIndices(
            operations = secondRead,
            duplicateMatches = matches,
            deterministicBlockedLineIndices = LedgerDeterministicObjections.blockedLineIndices(
                secondRead,
                existingCustomerCandidates = emptyList(),
                catalogProducts = emptyList(),
                pageDateEpochDay = pageDateEpochDay,
                todayEpochDay = todayEpochDay,
            ),
        )
        assertEquals(
            "el duplicado deja los dos renglones sin resolver hasta confirmarlos",
            setOf(0, 1),
            blocked,
        )

        // Y con los renglones bloqueados, la segunda sesión NO escribe: la puerta de escritura
        // recibe ese conjunto y el planificador no ejecuta nada.
        val second = kotlinx.coroutines.runBlocking {
            LedgerBatchCoordinator.confirm(
                businessId = businessId,
                userId = userId,
                importSessionId = "sesion-2",
                operations = secondRead,
                blockedLineIndices = blocked,
                modelSaysTextMayBeMissing = false,
                isPageCompletenessConfirmed = false,
                pageDateEpochDay = pageDateEpochDay,
                todayEpochDay = todayEpochDay,
                nowEpochMillis = nowEpochMillis,
                alreadyRecorded = { key -> writtenPlans.any { it.batchIdempotencyKey == key } },
                loadCustomersByNormalizedName = { emptyMap() },
                loadOutstandingByCustomerId = { emptyMap() },
                write = { plan ->
                    writtenPlans += plan
                    ExpectedLedgerBatchResult.Recorded(plan.batchIdempotencyKey, plan.executableOperationsCount)
                },
            )
        }
        assertEquals(
            "sin confirmar, el duplicado no se registra",
            LedgerBatchConfirmation.Failed,
            second,
        )
        assertEquals("no hubo segunda escritura", 1, writtenPlans.size)

        // Confirmar cada renglón ("Sí, es otra venta") los desbloquea y la página pasa.
        val confirmed = secondRead.map { LedgerObjectionConfirmation.confirming(it) }
        val blockedAfter = LedgerDuplicateBlocking.blockedLineIndices(
            operations = confirmed,
            duplicateMatches = matches,
            deterministicBlockedLineIndices = emptySet(),
        )
        assertTrue("tras confirmar, ya no queda nada bloqueado", blockedAfter.isEmpty())

        val third = kotlinx.coroutines.runBlocking {
            LedgerBatchCoordinator.confirm(
                businessId = businessId,
                userId = userId,
                importSessionId = "sesion-2",
                operations = confirmed,
                blockedLineIndices = blockedAfter,
                modelSaysTextMayBeMissing = false,
                isPageCompletenessConfirmed = false,
                pageDateEpochDay = pageDateEpochDay,
                todayEpochDay = todayEpochDay,
                nowEpochMillis = nowEpochMillis,
                alreadyRecorded = { key -> writtenPlans.any { it.batchIdempotencyKey == key } },
                loadCustomersByNormalizedName = { emptyMap() },
                loadOutstandingByCustomerId = { emptyMap() },
                write = { plan ->
                    writtenPlans += plan
                    ExpectedLedgerBatchResult.Recorded(plan.batchIdempotencyKey, plan.executableOperationsCount)
                },
            )
        }
        assertEquals(
            "confirmado como otra venta, el registro procede",
            LedgerBatchConfirmation.Recorded(2),
            third,
        )
        assertEquals(2, writtenPlans.size)
    }

    @Test
    fun `con la fecha imposible de v44 el mismo duplicado si quedaria sin resolver`() {
        // Contrapunto: la objeción de año imposible (parte B) SÍ bloquea, y un renglón
        // bloqueado cierra la página entera. Con v44 el duplicado también bloquea, por su
        // propio camino (`LedgerDuplicateBlocking`), así que las dos puertas cierran igual.
        val operations = listOf(ledgerRow(0, "Laura", "99900").copy(date = SafeDateCandidate(2023, 3, 1)))
        val blocked = LedgerDeterministicObjections.blockedLineIndices(
            operations,
            existingCustomerCandidates = emptyList(),
            catalogProducts = emptyList(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
        )
        assertEquals("la fecha 2023 sí deja el renglón sin resolver", setOf(0), blocked)
    }

    @Test
    fun `pagina de 2023 sin fechas por renglon ya registrada queda frenada la segunda vez`() {
        // El escenario de Codex: una página antigua de 2023 cuyos renglones NO traen fecha, pero cuya
        // fecha de página sí es 2023. La consulta a Room usaba sólo fechas de renglón y caía a hoy
        // (2026), así que no encontraba nada y el duplicado pasaba. Con la ventana de fecha efectiva
        // (renglón o página) la consulta cae en 2023 y el detector SÍ casa.
        val page2023 = LocalDate.of(2023, 3, 1).toEpochDay()
        val today2026 = LocalDate.of(2026, 9, 22).toEpochDay()
        val undatedPage = listOf(
            ledgerRow(0, "Laura", "99900").copy(date = null),
            ledgerRow(1, "Estef", "15000").copy(date = null),
        )

        // La primera sesión escribe la página (con la fecha de página como fecha efectiva).
        val first = kotlinx.coroutines.runBlocking {
            LedgerBatchCoordinator.confirm(
                businessId = businessId,
                userId = userId,
                importSessionId = "sesion-1",
                operations = undatedPage,
                blockedLineIndices = emptySet(),
                modelSaysTextMayBeMissing = false,
                isPageCompletenessConfirmed = false,
                pageDateEpochDay = page2023,
                todayEpochDay = today2026,
                nowEpochMillis = nowEpochMillis,
                alreadyRecorded = { key -> writtenPlans.any { it.batchIdempotencyKey == key } },
                loadCustomersByNormalizedName = { emptyMap() },
                loadOutstandingByCustomerId = { emptyMap() },
                write = { plan ->
                    writtenPlans += plan
                    ExpectedLedgerBatchResult.Recorded(plan.batchIdempotencyKey, plan.executableOperationsCount)
                },
            )
        }
        assertEquals(LedgerBatchConfirmation.Recorded(2), first)

        // La ventana de consulta que la pantalla usaría ahora: cae en 2023, no en 2026.
        val (min, max) = LedgerEffectiveDate.queryWindow(
            operations = undatedPage,
            pageDateEpochDay = page2023,
            todayEpochDay = today2026,
            windowDays = 14L,
        )
        assertEquals(page2023 - 14L, min)
        assertEquals(page2023 + 14L, max)

        // Lo que esa consulta devuelve: las ventas de la primera sesión, con su fecha efectiva.
        val registered = writtenPlans.first().sales.map { sale ->
            RegisteredOperationProjection(
                id = sale.id,
                customerId = sale.customerId,
                customerName = writtenPlans.first().newCustomers.first { it.id == sale.customerId }.name,
                amountCents = sale.totalCents,
                effectiveDateEpochDay = sale.saleDateEpochDay,
                kind = ProposedLedgerKind.SALE,
            )
        }

        // El detector casa los dos renglones (usa la fecha de página para los que no traen fecha).
        val matches = LedgerDuplicateDetector.evaluate(
            operations = undatedPage,
            existingRegistered = registered,
            pageDateEpochDay = page2023,
            todayEpochDay = today2026,
        )
        assertEquals("con la ventana correcta el detector sí casa", setOf(0, 1), matches.keys)

        // Y por eso la segunda vez queda frenada.
        val blocked = LedgerDuplicateBlocking.blockedLineIndices(
            operations = undatedPage,
            duplicateMatches = matches,
            deterministicBlockedLineIndices = emptySet(),
        )
        assertEquals("la segunda vez queda frenada", setOf(0, 1), blocked)
    }
}
