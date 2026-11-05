package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation

/** Decide si una página completa está lista para registrarse. */
object LedgerPageResolution {
    fun unresolvedLineIndices(operations: List<LedgerOperation>, blockedLineIndices: Set<Int>): Set<Int> =
        operations.filter { !isRegisterableLedgerOperation(it) || it.lineIndex in blockedLineIndices }
            .mapTo(LinkedHashSet()) { it.lineIndex }

    fun isPageResolved(operations: List<LedgerOperation>, blockedLineIndices: Set<Int>): Boolean =
        unresolvedLineIndices(operations, blockedLineIndices).isEmpty()
}
