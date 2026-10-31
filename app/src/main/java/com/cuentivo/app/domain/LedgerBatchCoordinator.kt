package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
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

    /** Confirma una página con una clave estable por negocio y sesión de importación. */
    suspend fun confirm(
        businessId: String,
        userId: String,
        importSessionId: String,
        operations: List<LedgerOperation>,
        blockedLineIndices: Set<Int>,
        modelSaysTextMayBeMissing: Boolean,
        isPageCompletenessConfirmed: Boolean,
        pageDateEpochDay: Long?,
        /** Plazo en días para los cargos de la página, o `null` para "Sin fecha". Ver [AiLedgerBatchPlanner.plan]. */
        dueDateDays: Long? = null,
        todayEpochDay: Long,
        nowEpochMillis: Long,
        alreadyRecorded: suspend (batchKey: String) -> Boolean,
        loadCustomersByNormalizedName: suspend () -> Map<String, CustomerEntity>,
        loadOutstandingByCustomerId: suspend () -> Map<String, List<OutstandingSale>>,
        write: suspend (PlannedLedgerBatch) -> ExpectedLedgerBatchResult,
        enqueue: suspend (PlannedLedgerBatch) -> Unit = {},
    ): LedgerBatchConfirmation {
        if (businessId.isBlank() || userId.isBlank() || importSessionId.isBlank()) return LedgerBatchConfirmation.Failed
        if (!LedgerPageCompleteness.isRegistrable(modelSaysTextMayBeMissing, isPageCompletenessConfirmed)) return LedgerBatchConfirmation.Failed
        val unresolved = LedgerPageResolution.unresolvedLineIndices(operations, blockedLineIndices)
        if (unresolved.isNotEmpty()) return LedgerBatchConfirmation.Failed
        val batchKey = AiLedgerBatchPlanner.batchKeyFor(businessId, importSessionId)
        if (alreadyRecorded(batchKey)) {
            return LedgerBatchConfirmation.AlreadyRecorded(operations.size)
        }

        val planned = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = importSessionId,
            operations = operations,
            blockedLineIndices = blockedLineIndices,
            existingCustomerMap = loadCustomersByNormalizedName(),
            existingOutstandingSalesByCustomerId = loadOutstandingByCustomerId(),
            pageDateEpochDay = pageDateEpochDay,
            dueDateDays = dueDateDays,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )
        if (planned !is BatchPlanningResult.Success) return LedgerBatchConfirmation.Failed

        return when (val result = write(planned.plan)) {
            is ExpectedLedgerBatchResult.Recorded -> {
                // After the money is committed, never before, and never allowed to turn a recorded
                // page into a reported failure.
                enqueue(planned.plan)
                LedgerBatchConfirmation.Recorded(result.executableOperationsCount)
            }
            is ExpectedLedgerBatchResult.AlreadyRecorded -> LedgerBatchConfirmation.AlreadyRecorded(result.executableOperationsCount)
            else -> LedgerBatchConfirmation.Failed
        }
    }
}
