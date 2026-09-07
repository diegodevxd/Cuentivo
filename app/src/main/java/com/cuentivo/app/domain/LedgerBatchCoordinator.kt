package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ExpectedLedgerBatchResult

/**
 * The order in which a reviewed page becomes debt.
 *
 * It lives here, and not inside the Activity, because the order itself is the guarantee: the ledger
 * is asked whether this page was already written **before** planning it again. Planning first looks
 * at a debt the page itself changed, so a payment that was in fact recorded would be replanned
 * against nothing left to pay, rejected, and reported to the person as a failure.
 *
 * Everything it touches arrives as a function, so the test exercises this coordination rather than
 * a copy of it written beside it.
 */
object LedgerBatchCoordinator {

    /** How many operations were written, or null when nothing was. */
    suspend fun confirm(
        businessId: String,
        userId: String,
        operations: List<LedgerOperation>,
        pageDateEpochDay: Long?,
        todayEpochDay: Long,
        nowEpochMillis: Long,
        alreadyRecorded: suspend (batchKey: String) -> Boolean,
        loadCustomersByNormalizedName: suspend () -> Map<String, CustomerEntity>,
        loadOutstandingByCustomerId: suspend () -> Map<String, List<OutstandingSale>>,
        write: suspend (PlannedLedgerBatch) -> ExpectedLedgerBatchResult,
        enqueue: suspend (PlannedLedgerBatch) -> Unit = {},
    ): Int? {
        if (businessId.isBlank() || userId.isBlank()) return null

        val registrable = operations.filter { isRegisterableLedgerOperation(it) }
        val resolvedDates = AiLedgerBatchPlanner.resolvedDatesFor(
            operations = operations,
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
        )
        val batchKey = AiLedgerBatchPlanner.batchKeyFor(
            businessId = businessId,
            registrableOperations = registrable,
            resolvedDatesByLineIndex = resolvedDates,
        )
        if (alreadyRecorded(batchKey)) {
            return registrable.size
        }

        val planned = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = operations,
            existingCustomerMap = loadCustomersByNormalizedName(),
            existingOutstandingSalesByCustomerId = loadOutstandingByCustomerId(),
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )
        if (planned !is BatchPlanningResult.Success) return null

        return when (val result = write(planned.plan)) {
            is ExpectedLedgerBatchResult.Recorded -> {
                // After the money is committed, never before, and never allowed to turn a recorded
                // page into a reported failure.
                enqueue(planned.plan)
                result.executableOperationsCount
            }
            else -> null
        }
    }
}
