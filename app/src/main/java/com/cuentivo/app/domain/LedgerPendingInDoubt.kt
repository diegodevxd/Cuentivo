package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.CustomerReviewGroup
import com.cuentivo.app.ai.ledger.ProposedLedgerKind

/**
 * Cuánto dinero de un cliente está todavía en duda y por eso **no** entra al saldo resultante.
 *
 * En la revisión, un renglón con campos pendientes u objeción no es registrable, así que no suma al
 * `resultingBalanceCents` del grupo. Eso está bien —no se escribe— pero deja un "Saldo: $0.00 →
 * $0.00" que parece decir que no se va a anotar nada, cuando hay un cargo de $999 esperando a que
 * alguien lo resuelva (recorrido "Doña Mary", v44).
 *
 * Sólo cuenta cargos ([ProposedLedgerKind.SALE] y [ProposedLedgerKind.PRIOR_DEBT]): un abono en duda
 * no aumentaría el saldo pendiente, y mezclarlo daría un número que no es "lo que podría deber".
 */
object LedgerPendingInDoubt {

    fun chargeCents(group: CustomerReviewGroup): Long {
        val executableLineIndices = group.executableOperations.mapTo(HashSet()) { it.lineIndex }
        return group.operations
            .filter { it.lineIndex !in executableLineIndices }
            .filter {
                it.proposedKind == ProposedLedgerKind.SALE ||
                    it.proposedKind == ProposedLedgerKind.PRIOR_DEBT
            }
            .mapNotNull { it.amountCents?.toLongOrNull() }
            .filter { it > 0L }
            .fold(0L) { acc, cents -> runCatching { Math.addExact(acc, cents) }.getOrDefault(acc) }
    }
}
