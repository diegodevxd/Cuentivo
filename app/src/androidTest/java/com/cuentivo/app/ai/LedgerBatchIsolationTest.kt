package com.cuentivo.app.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ExpectedLedgerBatchResult
import com.cuentivo.app.domain.AiLedgerBatchPlanner
import com.cuentivo.app.domain.BatchPlanningResult
import com.cuentivo.app.domain.LedgerBatchConfirmation
import com.cuentivo.app.domain.LedgerBatchCoordinator
import com.cuentivo.app.domain.OutstandingSale
import com.cuentivo.app.domain.SaleRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page of a paper notebook becoming debt in the ledger, against real Room.
 *
 * These are the cases the plan says the batch exists to solve, and none of them can be shown with a
 * fake repository: two payments from the same customer, one customer spread over several lines, and
 * a confirmation that arrives twice. The balance is always read back through the same query
 * production uses, never recomputed by the test.
 */
@RunWith(AndroidJUnit4::class)
class LedgerBatchIsolationTest {

    private lateinit var database: CuentivoDatabase
    private lateinit var saleRepository: SaleRepository

    private val businessId = "biz-ledger-batch"
    private val userId = "user-ledger-batch"
    // 2026-06-01, comfortably after the notebook dates the tests use: a date in the future is
    // not a notebook date and the planner refuses it, as it should.
    private val todayEpochDay = 20_605L
    private val nowEpochMillis = 1_700_000_000_000L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()
        saleRepository = SaleRepository(database.financialDao())

        database.businessDao().insert(
            BusinessEntity(
                id = businessId,
                name = "Abarrotes Libreta",
                ownerUserId = userId,
                currency = "MXN",
                timezone = "America/Mexico_City",
                createdAtEpochMillis = 10_000L,
                updatedAtEpochMillis = 10_000L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // `rawText` es opcional en v4 (`ledgerExtraction.ts` sólo lo manda cuando lo tiene), pero sin él
    // la comprobación del cliente existente no puede verificar que la hoja nombre a esa persona y
    // objeta —el lado seguro—, así que la página entera se rechaza. Estos renglones traen texto
    // realista para que prueben lo que dicen: el nombre aparece como token completo, como en el papel.
    private fun sale(lineIndex: Int, name: String, cents: String, day: Int = 10) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = cents,
        rawText = "$name $cents",
        date = SafeDateCandidate(2026, 3, day),
    )

    private fun payment(lineIndex: Int, name: String, cents: String, day: Int = 12) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.PAYMENT,
        customerName = name,
        amountCents = cents,
        rawText = "$name $cents",
        date = SafeDateCandidate(2026, 3, day),
    )

    private suspend fun existingCustomers(): Map<String, CustomerEntity> =
        database.customerDao().activeWithBalances(businessId).associate { it.customer.normalizedName to it.customer }

    private suspend fun outstandingByCustomer(): Map<String, List<OutstandingSale>> =
        database.customerDao().activeWithBalances(businessId).associate { row ->
            row.customer.id to database.financialDao()
                .outstandingSalesForCustomer(businessId, row.customer.id)
                .map { OutstandingSale(it.id, it.outstandingCents, it.dueDateEpochDay, it.saleDateEpochDay) }
        }

    /** Cada página tiene su propia sesión, igual que en producción, donde es un UUID por captura. */
    private fun page(id: String) = "session-$id"

    private suspend fun planFor(
        sessionId: String,
        operations: List<LedgerOperation>,
        dueDateDays: Long? = null,
    ): BatchPlanningResult =
        AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = sessionId,
            operations = operations,
            blockedLineIndices = emptySet(),
            existingCustomerMap = existingCustomers(),
            existingOutstandingSalesByCustomerId = outstandingByCustomer(),
            pageDateEpochDay = todayEpochDay,
            dueDateDays = dueDateDays,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

    /**
     * Por qué no se escribió. Sin esto, un rechazo sólo dice "The page must be recorded" y no hay
     * forma de saber si fue el planificador, la reserva ya tomada o una duda de hoja: la ceguera que
     * costó una ronda entera de diagnóstico. Todo sale de código de producción (`planFor` y la
     * consulta de la reserva), no de una cuenta hecha aquí.
     */
    private suspend fun explain(sessionId: String, operations: List<LedgerOperation>): String {
        val key = AiLedgerBatchPlanner.batchKeyFor(businessId, sessionId)
        val reserved = database.financialDao().operationResultId(key) != null
        val plan = when (val planned = planFor(sessionId, operations)) {
            is BatchPlanningResult.Success -> "Success(${planned.plan.executableOperationsCount} ops)"
            is BatchPlanningResult.Rejected -> "Rejected(${planned.rejections.joinToString()})"
        }
        return "sesion=$sessionId, clave=${if (reserved) "YA RESERVADA por una pagina anterior" else "libre"}, plan=$plan"
    }

    /**
     * Goes through the very coordination the app uses, so removing the lookup that production
     * depends on turns these tests red instead of leaving them green.
     */
    private suspend fun confirm(
        sessionId: String,
        operations: List<LedgerOperation>,
        dueDateDays: Long? = null,
    ): LedgerBatchConfirmation =
        LedgerBatchCoordinator.confirm(
            businessId = businessId,
            userId = userId,
            importSessionId = sessionId,
            operations = operations,
            blockedLineIndices = emptySet(),
            // Estos renglones simulan una hoja que el modelo leyó entera; la duda de página tiene su
            // propia prueba en JVM (`LedgerPageCompletenessTest`).
            modelSaysTextMayBeMissing = false,
            isPageCompletenessConfirmed = false,
            pageDateEpochDay = todayEpochDay,
            dueDateDays = dueDateDays,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
            alreadyRecorded = { key -> database.financialDao().operationResultId(key) != null },
            loadCustomersByNormalizedName = { existingCustomers() },
            loadOutstandingByCustomerId = { outstandingByCustomer() },
            write = { plan -> saleRepository.registerLedgerBatch(plan) },
        )

    private suspend fun write(
        sessionId: String,
        operations: List<LedgerOperation>,
        dueDateDays: Long? = null,
    ): ExpectedLedgerBatchResult {
        val result = confirm(sessionId, operations, dueDateDays)
        assertTrue(
            "The page must be recorded, but the coordinator answered $result " +
                "[${explain(sessionId, operations)}]: $operations",
            result is LedgerBatchConfirmation.Recorded,
        )
        return ExpectedLedgerBatchResult.Recorded(
            "checked-by-coordinator",
            (result as LedgerBatchConfirmation.Recorded).operationsCount,
        )
    }

    private suspend fun balanceOf(name: String): Long {
        val customer = database.customerDao().activeWithBalances(businessId)
            .first { it.customer.normalizedName == name }
        return database.financialDao().customerBalanceCents(businessId, customer.customer.id)
    }

    @Test
    fun a_page_of_sales_becomes_debt_for_each_customer() = runBlocking {
        val result = write(page("ventas"), listOf(sale(0, "Rosa", "25000"), sale(1, "Juan", "30000")))

        assertTrue(result is ExpectedLedgerBatchResult.Recorded)
        assertEquals(25_000L, balanceOf("rosa"))
        assertEquals(30_000L, balanceOf("juan"))
    }

    @Test
    fun one_customer_across_three_lines_is_created_once() = runBlocking {
        write(page("tres-renglones"), listOf(sale(0, "Rosa", "10000"), sale(1, "Rosa", "20000"), sale(2, "Rosa", "5000")))

        val rosas = database.customerDao().activeWithBalances(businessId)
            .filter { it.customer.normalizedName == "rosa" }
        assertEquals("Rosa must exist once, not once per line", 1, rosas.size)
        assertEquals(35_000L, balanceOf("rosa"))
    }

    @Test
    fun two_payments_from_the_same_customer_are_both_applied() = runBlocking {
        // The reason this batch is atomic: applied one at a time, the second payment would be
        // rejected for disagreeing with the balance the first one just changed.
        // Dos PÁGINAS distintas: la venta que crea la deuda y luego los abonos. Cada una con su
        // sesión, como en producción, donde la sesión es un UUID por captura. Con una sola sesión
        // para las dos, la segunda página se tomaría por una repetición de la primera.
        write(page("venta-rosa"), listOf(sale(0, "Rosa", "50000")))

        val result = write(page("abonos-rosa"), listOf(payment(1, "Rosa", "20000"), payment(2, "Rosa", "15000")))

        assertTrue(result is ExpectedLedgerBatchResult.Recorded)
        assertEquals(15_000L, balanceOf("rosa"))
    }

    @Test
    fun confirming_the_same_page_twice_records_it_once() = runBlocking {
        val operations = listOf(sale(0, "Rosa", "25000"), sale(1, "Juan", "30000"))
        val session = page("misma-pagina")

        write(session, operations)
        val balanceAfterFirst = balanceOf("rosa")
        val salesAfterFirst = database.financialDao().registeredSaleCount(businessId)
        val auditAfterFirst = database.auditDao().forBusiness(businessId).size

        // La MISMA página otra vez: misma sesión. Tiene que reconocerla y, sobre todo, decir que ya
        // estaba registrada en vez de "registradas" — eso último es lo que el item 4 arregló.
        assertEquals(
            "una página ya escrita se reporta como ya registrada, no como nueva",
            LedgerBatchConfirmation.AlreadyRecorded(2),
            confirm(session, operations),
        )

        assertEquals(balanceAfterFirst, balanceOf("rosa"))
        assertEquals(salesAfterFirst, database.financialDao().registeredSaleCount(businessId))
        assertEquals(auditAfterFirst, database.auditDao().forBusiness(businessId).size)
    }

    @Test
    fun a_page_with_one_unreadable_row_is_rejected_whole_and_writes_nothing() = runBlocking {
        // Una página se registra entera o no se registra. Antes se escribía el renglón legible y se
        // omitía el ilegible en silencio; eso es lo que hacía cambiar el conjunto escribible entre
        // intentos y podía escribir un cargo dos veces al reintentar. Ahora se rechaza la página
        // completa y no queda nada a medias.
        val unreadable: LedgerOperation = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = null,
            unreadableFields = listOf(com.cuentivo.app.ai.ledger.UnreadableLedgerField.AMOUNT),
        )

        val result = confirm(page("ilegible"), listOf(sale(0, "Rosa", "25000"), unreadable))

        assertEquals(
            "una página con un renglón ilegible se rechaza entera",
            com.cuentivo.app.domain.LedgerBatchConfirmation.Failed,
            result,
        )
        assertEquals(
            "no se escribe ningún movimiento",
            0,
            database.financialDao().allMovementsForBackfill(businessId).size,
        )
        assertEquals("no se escribe ninguna venta", 0, database.financialDao().registeredSaleCount(businessId))
        assertEquals(
            "el renglón legible tampoco crea su clienta",
            0,
            database.customerDao().activeWithBalances(businessId).count { it.customer.normalizedName == "rosa" },
        )
    }

    @Test
    fun a_failure_after_inserting_leaves_absolutely_nothing() = runBlocking {
        // A batch is all or nothing. This forces the rejection to be discovered while rows are
        // already being written, which is the moment where a half written page would survive.
        write(page("preparacion"), listOf(sale(0, "Rosa", "25000")))
        val salesBefore = database.financialDao().registeredSaleCount(businessId)
        val auditBefore = database.auditDao().forBusiness(businessId).size
        val balanceBefore = balanceOf("rosa")

        // Página aparte, con sesión propia: si compartiera la de la venta de arriba, su clave ya
        // estaría reservada y el lote roto no llegaría ni a intentarse.
        val session = page("rota")
        val planned = planFor(session, listOf(sale(1, "Nuevo Cliente", "40000"), sale(2, "Otro Cliente", "10000")))
        val plan = (planned as BatchPlanningResult.Success).plan
        // The failure has to be discovered while rows are already going in, not during the checks
        // that run before the first insert. A duplicated sale id passes every validation and blows
        // up on the second INSERT, which is exactly the moment a half written page would survive.
        val broken = plan.copy(
            sales = plan.sales + plan.sales.first().copy(saleNumber = plan.sales.first().saleNumber + "-dup"),
        )

        var threw = false
        try {
            saleRepository.registerLedgerBatch(broken)
        } catch (_: Exception) {
            threw = true
        }

        assertTrue("A batch that cannot be written whole must be refused", threw)
        assertEquals(
            "Nothing of the refused batch may remain, movements included",
            0,
            database.financialDao().allMovementsForBackfill(businessId).count { movement ->
                plan.chargeMovements.any { it.id == movement.id }
            },
        )
        assertEquals(salesBefore, database.financialDao().registeredSaleCount(businessId))
        assertEquals(auditBefore, database.auditDao().forBusiness(businessId).size)
        assertEquals(balanceBefore, balanceOf("rosa"))
        assertTrue(
            "No customer from the refused batch may survive",
            database.customerDao().activeWithBalances(businessId)
                .none { it.customer.normalizedName == "nuevo cliente" || it.customer.normalizedName == "otro cliente" },
        )
        // And the key must be free, so correcting the page and confirming again still works.
        val retry = write(session, listOf(sale(1, "Nuevo Cliente", "40000"), sale(2, "Otro Cliente", "10000")))
        assertTrue(retry is ExpectedLedgerBatchResult.Recorded)
        assertEquals(40_000L, balanceOf("nuevo cliente"))
    }

    @Test
    fun two_confirmations_at_once_write_the_page_once() = runBlocking {
        val operations = listOf(sale(0, "Rosa", "25000"), sale(1, "Juan", "30000"))
        val planned = (planFor(page("concurrente"), operations) as BatchPlanningResult.Success).plan

        val results = listOf(
            async { runCatching { saleRepository.registerLedgerBatch(planned) } },
            async { runCatching { saleRepository.registerLedgerBatch(planned) } },
        ).awaitAll()

        assertTrue("Both confirmations must be answered", results.all { it.isSuccess })
        assertEquals(25_000L, balanceOf("rosa"))
        assertEquals(2, database.financialDao().registeredSaleCount(businessId))
    }

    @Test
    fun reconfirming_a_page_of_payments_reports_the_same_thing() = runBlocking {
        // A payment that settles the debt completely: planning it a second time would find nothing
        // left to pay and reject it, so passing proves the batch is recognised by its key.
        write(page("venta-rosa-2"), listOf(sale(0, "Rosa", "50000")))
        val payments = listOf(payment(1, "Rosa", "50000"))
        val session = page("abono-total")

        val first = write(session, payments)
        val balanceAfterFirst = balanceOf("rosa")
        // La misma página de abonos otra vez, con su misma sesión. El pago la saldó entera, así que
        // replanificarla la rechazaría por no quedar deuda: pasar prueba que se reconoce por su clave.
        // Y lo que tiene que reportar es "ya registrada", no un segundo registro.
        val second = confirm(session, payments)

        assertTrue(first is ExpectedLedgerBatchResult.Recorded)
        assertEquals(
            "Re-confirming must report it as already recorded, not as a new registration",
            LedgerBatchConfirmation.AlreadyRecorded(1),
            second,
        )
        assertEquals(balanceAfterFirst, balanceOf("rosa"))
    }

    @Test
    fun the_audit_log_names_each_written_row() = runBlocking {
        write(page("auditoria"), listOf(sale(0, "Rosa", "25000"), sale(1, "Juan", "30000")))

        val audit = database.auditDao().forBusiness(businessId)
        val saleEvents = audit.filter { it.entityType == "SALE" }
        assertEquals("One audit row per sale, not one for the whole page", 2, saleEvents.size)
        assertEquals(2, saleEvents.map { it.entityId }.distinct().size)
    }

    /**
     * El flujo completo de la Parte A del encargo: una página fotografiada, con plazo elegido en
     * la revisión, registrada contra Room **de verdad**, y después leída por la misma consulta que
     * alimenta el recordatorio de cobro y el filtro de vencidos.
     *
     * Sin plazo la venta no puede salir ahí (`dueDateEpochDay IS NOT NULL` es parte del WHERE), y
     * esa es exactamente el hueco que este cambio cierra: por eso la página de Beto, escrita sin
     * plazo, no aparece.
     */
    @Test
    fun con_plazo_la_venta_importada_aparece_en_salesWithDueDate() = runBlocking {
        // La venta de la libreta es del 10 de marzo; con 15 dias vence el 25 de marzo.
        write(page("plazo-15"), listOf(sale(0, "Rosa", "25000")), dueDateDays = 15)
        // La misma pagina de siempre pero sin plazo: el comportamiento anterior.
        write(page("sin-plazo"), listOf(sale(1, "Beto", "12500")))

        val dueRows = database.financialDao().salesWithDueDate(businessId)

        assertEquals(
            "solo la venta con plazo entra al recordatorio de cobro",
            1,
            dueRows.size,
        )
        assertEquals("Rosa", dueRows.single().customerName)
        assertEquals(
            "la fecha limite se cuenta desde la fecha del renglon + 15, no desde hoy",
            java.time.LocalDate.of(2026, 3, 10).toEpochDay() + 15,
            dueRows.single().dueDateEpochDay,
        )
        assertEquals(25_000L, dueRows.single().outstandingCents)

        // Y la ve tambien el calculador de estado que clasifica las ventas: la del 10 de marzo
        // con vencimiento el 25 de marzo, leidos contra el hoy de esta prueba, es OVERDUE.
        assertEquals(
            com.cuentivo.app.domain.SalePaymentStatus.OVERDUE,
            com.cuentivo.app.domain.SaleStatusCalculator.status(
                totalCents = 25_000L,
                paidCents = 0L,
                dueDateEpochDay = dueRows.single().dueDateEpochDay,
                todayEpochDay = todayEpochDay,
                isCanceled = false,
            ),
        )
    }
}
