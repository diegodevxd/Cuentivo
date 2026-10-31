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
 * El plazo de pago de una página de libreta, sobre el planner y el objeto puro.
 *
 * Dos reglas viven aquí y ninguna se reimplementa en la prueba: la fecha que guarda el planificador
 * se lee del plan que produjo, y la decisión de "vencida" la toma el mismo `SaleStatusCalculator`
 * que después clasifica la venta ya escrita. Si esos objetos cambian, estas pruebas caen.
 *
 * Datos inventados siempre, y el "hoy" fijado: el objeto no lee el reloj.
 */
class LedgerDueDatePolicyTest {

    private val businessId = "biz-plazo"
    private val userId = "user-plazo"
    // 2026-09-22, hoy del encargo. Todas las fechas de esta clase se cuentan desde aquí.
    private val todayEpochDay = LocalDate.of(2026, 9, 22).toEpochDay()
    private val nowEpochMillis = 1_760_000_000_000L

    private fun sale(lineIndex: Int, name: String, cents: String, day: Int) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = cents,
        date = SafeDateCandidate(2026, 9, day),
    )

    private fun payment(lineIndex: Int, name: String, cents: String, day: Int) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.PAYMENT,
        customerName = name,
        amountCents = cents,
        date = SafeDateCandidate(2026, 9, day),
    )

    private fun plan(operations: List<LedgerOperation>, dueDateDays: Long?): PlannedLedgerBatch {
        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = "session-${operations.joinToString { it.amountCents ?: "" }}-$dueDateDays",
            operations = operations,
            blockedLineIndices = emptySet(),
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            dueDateDays = dueDateDays,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )
        assertTrue("el plan tuvo que salir: ${result}", result is BatchPlanningResult.Success)
        return (result as BatchPlanningResult.Success).plan
    }

    // ---- Lo que guarda el planner ----

    @Test
    fun `con 15 dias cada cargo lleva su fecha de venta mas 15`() {
        val planned = plan(
            operations = listOf(sale(0, "Rosa", "25000", 1), sale(1, "Rosa", "30000", 10)),
            dueDateDays = 15,
        )

        val dueBySaleDate = planned.sales.associate { it.saleDateEpochDay to it.dueDateEpochDay }
        assertEquals(2, planned.sales.size)
        for (sale in planned.sales) {
            assertEquals(
                "la fecha limite se cuenta desde la fecha del renglon, no desde hoy",
                sale.saleDateEpochDay + 15,
                sale.dueDateEpochDay,
            )
        }
        // Y no se me va el detalle: una venta del dia 1 vence el 16, aunque hoy sea el 22.
        val first = planned.sales.first { it.saleDateEpochDay == LocalDate.of(2026, 9, 1).toEpochDay() }
        assertEquals(LocalDate.of(2026, 9, 16).toEpochDay(), first.dueDateEpochDay)
        assertEquals(2, dueBySaleDate.size)
    }

    @Test
    fun `sin fecha los cargos siguen naciendo sin fecha limite`() {
        // Lo de hoy, y lo que "Sin fecha" tiene que seguir significando.
        for (dueDateDays in listOf(null, null as Long?)) {
            val planned = plan(listOf(sale(0, "Beto", "12500", 5)), dueDateDays)
            assertNull("sin plazo no puede haber fecha limite", planned.sales.single().dueDateEpochDay)
        }
        // También el que no se pasa: el parametro por defecto es el comportamiento de siempre.
        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = "session-sin-parametro",
            operations = listOf(sale(0, "Beto", "12500", 5)),
            blockedLineIndices = emptySet(),
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )
        val planned = (result as BatchPlanningResult.Success).plan
        assertNull(planned.sales.single().dueDateEpochDay)
    }

    @Test
    fun `los abonos no llevan fecha limite ni la suman a su propia fecha`() {
        // La unica fecha que tiene un abono es la suya. Si el plazo se le aplicara, el abono del
        // dia 12 saldria con fecha de pago "27 de septiembre", que no es nada: el pago ocurrio el 12.
        val planned = plan(
            operations = listOf(sale(0, "Rosa", "50000", 1), payment(1, "Rosa", "20000", 12)),
            dueDateDays = 15,
        )

        val paymentRow = planned.payments.single()
        assertEquals(
            "el plazo no se cuela en la fecha del abono",
            LocalDate.of(2026, 9, 12).toEpochDay(),
            paymentRow.paymentDateEpochDay,
        )
        // Y los cargos de la misma pagina si lo llevan.
        assertEquals(LocalDate.of(2026, 9, 16).toEpochDay(), planned.sales.single().dueDateEpochDay)
    }

    // ---- Lo que avisa la pantalla ----

    @Test
    fun `un cargo de hace 40 dias con 15 dias nace vencido y uno de ayer no`() {
        val oldDay = LocalDate.of(2026, 8, 13) // hace 40 dias
        val yesterday = LocalDate.of(2026, 9, 21)
        val operations = listOf(
            LedgerOperation(
                lineIndex = 0,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Sra. Mendoza",
                amountCents = "79900",
                date = SafeDateCandidate(oldDay.year, oldDay.monthValue, oldDay.dayOfMonth),
            ),
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Beto",
                amountCents = "12500",
                date = SafeDateCandidate(yesterday.year, yesterday.monthValue, yesterday.dayOfMonth),
            ),
        )

        val count = LedgerDueDatePolicy.bornOverdueCount(
            operations = operations,
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            dueDateDays = 15,
        )

        assertEquals("solo la venta vieja nace vencida", 1, count)
        // Los dos casos por separado, para que la regla no pueda quedar indistinguible: con la
        // relación invertida ("vencida" = lo que NO vence), los dos juntos seguirían sumando 1.
        assertEquals(
            "la de hace 40 dias si cuenta",
            1,
            LedgerDueDatePolicy.bornOverdueCount(
                operations = operations.take(1),
                pageDateEpochDay = null,
                todayEpochDay = todayEpochDay,
                dueDateDays = 15,
            ),
        )
        assertEquals(
            "la de ayer no cuenta: vence en 14 dias",
            0,
            LedgerDueDatePolicy.bornOverdueCount(
                operations = operations.drop(1),
                pageDateEpochDay = null,
                todayEpochDay = todayEpochDay,
                dueDateDays = 15,
            ),
        )
        // La de ayer vence en 14 dias: hoy esta pendiente, no vencida.
        val yesterdaySale = plan(operations, 15).sales.first { it.saleDateEpochDay == yesterday.toEpochDay() }
        assertEquals(
            SalePaymentStatus.PENDING,
            SaleStatusCalculator.status(
                totalCents = 12_500,
                paidCents = 0,
                dueDateEpochDay = yesterdaySale.dueDateEpochDay,
                todayEpochDay = todayEpochDay,
            ),
        )
    }

    @Test
    fun `sin plazo no hay aviso porque una venta sin fecha no puede vencerse`() {
        val operations = listOf(
            LedgerOperation(
                lineIndex = 0,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Sra. Mendoza",
                amountCents = "79900",
                date = SafeDateCandidate(2026, 8, 13),
            ),
        )

        assertEquals(
            0,
            LedgerDueDatePolicy.bornOverdueCount(
                operations = operations,
                pageDateEpochDay = null,
                todayEpochDay = todayEpochDay,
                dueDateDays = null,
            ),
        )
    }

    @Test
    fun `los abonos y los renglones bloqueados no cuentan`() {
        val operations = listOf(
            // Un abono viejo: los abonos no vencen.
            payment(0, "Rosa", "20000", 1),
            // Un cargo bloqueado por objecion: la pagina no lo registraria.
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Beto",
                amountCents = "12500",
                date = SafeDateCandidate(2026, 8, 13),
            ),
            // El unico cargado que si entra.
            LedgerOperation(
                lineIndex = 2,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Sra. Mendoza",
                amountCents = "79900",
                date = SafeDateCandidate(2026, 8, 13),
            ),
        )

        val count = LedgerDueDatePolicy.bornOverdueCount(
            operations = operations,
            blockedLineIndices = setOf(1),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            dueDateDays = 15,
        )

        assertEquals("ni el abono ni el renglon bloqueado", 1, count)
    }

    /**
     * La prueba que impide que el aviso y el plan digan cosas distintas: el conteo tiene que ser
     * exactamente cuántas de **las ventas que después escribe el planner** clasifica
     * `SaleStatusCalculator` como vencidas. No compara contra un número escrito a mano — invoca a
     * los dos productores reales.
     */
    @Test
    fun `el_conteo_coincide_con_lo_que_saleStatusCalculator_dice_de_las_ventas_que_se_escriben`() {
        val operations = listOf(
            LedgerOperation(
                lineIndex = 0,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Rosa",
                amountCents = "10000",
                date = SafeDateCandidate(2026, 8, 13), // hace 40 dias -> vencida
            ),
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Rosa",
                amountCents = "20000",
                date = SafeDateCandidate(2026, 9, 21), // ayer -> no
            ),
            LedgerOperation(
                lineIndex = 2,
                proposedKind = ProposedLedgerKind.PRIOR_DEBT,
                customerName = "Beto",
                amountCents = "5000",
                date = SafeDateCandidate(2026, 8, 20), // vencida con 15
            ),
            payment(3, "Rosa", "5000", 1),
        )
        val dueDateDays = 15L

        val announced = LedgerDueDatePolicy.bornOverdueCount(
            operations = operations,
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            dueDateDays = dueDateDays,
        )
        val planned = plan(operations, dueDateDays)
        val actuallyOverdueOnceWritten = planned.sales.count { sale ->
            SaleStatusCalculator.status(
                totalCents = sale.totalCents,
                paidCents = 0,
                dueDateEpochDay = sale.dueDateEpochDay,
                todayEpochDay = todayEpochDay,
            ) == SalePaymentStatus.OVERDUE
        }

        assertEquals(
            "el aviso tiene que contar lo que despues el calculador de produccion marca como vencido",
            actuallyOverdueOnceWritten,
            announced,
        )
        // Por si ambos se quedaran en cero por un fallo comun: la pagina si trae vencidas.
        assertEquals(2, announced)
    }
}
