package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation

/**
 * El plazo de pago de una página de libreta, en un solo sitio puro.
 *
 * Dos decisiones viven aquí, y las dos tienen que decir lo mismo:
 *
 * 1. [dueDateEpochDay] es la fecha límite que [AiLedgerBatchPlanner] guarda en cada cargo.
 * 2. [bornOverdueCount] es el número que la pantalla de revisión avisa bajo el selector.
 *
 * Si cada una calculara por su lado, el aviso podría prometer "ninguna vencida" mientras el plan
 * escribe vencidas — o lo contrario, que asusta sin motivo. Por eso el aviso **invoca el
 * planificador vía [dueDateEpochDay] y el calculador de producción [SaleStatusCalculator]**, nunca
 * una copia de la regla: si la regla cambia, cambian las dos caras a la vez.
 *
 * Kotlin puro, sin `Context`, sin reloj: la fecha de hoy entra como parámetro, igual que en el
 * resto de `domain/`, para que las pruebas la fijen.
 */
object LedgerDueDatePolicy {

    /**
     * La fecha límite de un cargo: `fecha de venta + días`, o `null` cuando no hay plazo.
     *
     * Sólo aplica a cargos. Un abono no vence: su fecha es la del propio abono
     * (`paymentDateEpochDay`) y esta función no se le aplica.
     */
    fun dueDateEpochDay(saleDateEpochDay: Long, dueDateDays: Long?): Long? =
        dueDateDays?.let { saleDateEpochDay + it }

    /**
     * Cuántos cargos de esta página **nacerían vencidos** si se registran hoy con [dueDateDays].
     *
     * Es el aviso que no puede faltar cuando la persona fotografía una página vieja: con 15 días
     * marcados, una venta del 1 de agosto nace vencida desde el 16 de agosto. Puede ser correcto,
     * pero no puede pasar sin que la persona lo vea antes de pulsar Registrar.
     *
     * Cuenta exactamente lo que después se escribiría:
     * - sólo cargos (`SALE` y `PRIOR_DEBT`) que sean registrables — un abono nunca vence;
     * - sin los renglones [blockedLineIndices] (los de objeción), que bloquean la página entera;
     * - con la fecha efectiva del renglón, resuelta por el mismo [AiLedgerBatchPlanner.resolveDate]
     *   que usará el plan — no "hoy" —, porque una venta vieja vence contado desde su fecha;
     * - y la decisión de "vencida" la toma [SaleStatusCalculator], el mismo que después clasifica
     *   la venta ya escrita. Aquí no se reimplementa esa regla: se le pregunta.
     */
    fun bornOverdueCount(
        operations: List<LedgerOperation>,
        blockedLineIndices: Set<Int> = emptySet(),
        pageDateEpochDay: Long?,
        todayEpochDay: Long,
        dueDateDays: Long?,
    ): Int {
        // Sin plazo no hay fecha límite, y una venta sin fecha no puede vencerse: cero.
        if (dueDateDays == null) return 0

        return operations.count { op ->
            if (!isCharge(op)) return@count false
            if (op.lineIndex in blockedLineIndices) return@count false
            if (!isRegisterableLedgerOperation(op)) return@count false

            val amountCents = op.amountCents?.toLongOrNull() ?: return@count false
            // Un cargo de $0 el planner lo rechaza (ZERO_AMOUNT): no se escribiría, no se cuenta.
            if (amountCents <= 0L) return@count false

            val saleDateEpochDay = AiLedgerBatchPlanner.resolveDate(
                op.date,
                pageDateEpochDay,
                todayEpochDay,
            ) ?: return@count false

            val dueDateEpochDay = dueDateEpochDay(saleDateEpochDay, dueDateDays) ?: return@count false

            SaleStatusCalculator.status(
                totalCents = amountCents,
                paidCents = 0L,
                dueDateEpochDay = dueDateEpochDay,
                todayEpochDay = todayEpochDay,
                isCanceled = false,
            ) == SalePaymentStatus.OVERDUE
        }
    }

    /** Sólo los cargos llevan fecha límite; los abonos, nunca. */
    private fun isCharge(op: LedgerOperation): Boolean =
        op.proposedKind == ProposedLedgerKind.SALE || op.proposedKind == ProposedLedgerKind.PRIOR_DEBT
}
