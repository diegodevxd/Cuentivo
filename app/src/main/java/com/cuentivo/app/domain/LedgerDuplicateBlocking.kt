package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation

/**
 * Qué renglones quedan sin resolver por un posible duplicado (v44, decisión de Diego 2026-09-22).
 *
 * El duplicado se trata **como las objeciones deterministas**: ni sólo avisar ni bloquear para
 * siempre. El renglón queda frenado hasta que la persona confirme que es otra venta, y una compra
 * repetida legítima —la misma clienta, lo mismo, dos veces en la semana— sigue siendo registrable
 * después de confirmarla.
 *
 * La confirmación reutiliza el mecanismo de las objeciones ([LedgerObjectionConfirmation]): la
 * huella se guarda en el renglón y, si la persona **corrige** el renglón hasta que deje de casar,
 * la huella deja de coincidir y la duda desaparece sola. Por eso aquí sólo se mira la huella, no
 * el contenido del match: un renglón confirmado y luego editado vuelve a quedar frenado si sigue
 * casando, que es exactamente lo que se quiere.
 *
 * Puro: cero Android, cero Room, cero red. La pantalla y la puerta de escritura lo invocan tal cual.
 */
object LedgerDuplicateBlocking {

    /**
     * Los `lineIndex` que un duplicado deja sin resolver: los que casan y **no** están confirmados.
     *
     * @param operations los renglones tal como están ahora (ya editados).
     * @param duplicateMatches lo que devolvió [LedgerDuplicateDetector.evaluate] para esos renglones.
     */
    fun blockedLineIndices(
        operations: List<LedgerOperation>,
        duplicateMatches: Map<Int, LedgerDuplicateMatch>,
    ): Set<Int> {
        if (duplicateMatches.isEmpty()) return emptySet()
        val byLine = operations.associateBy { it.lineIndex }
        return duplicateMatches.keys
            .filter { lineIndex ->
                val op = byLine[lineIndex] ?: return@filter true
                !LedgerObjectionConfirmation.isConfirmed(op)
            }
            .toCollection(LinkedHashSet())
    }

    /**
     * Los renglones que la página no puede registrar todavía: los que el duplicado frena, sumados a
     * los que ya frenaban las objeciones deterministas. Un solo conjunto para la pantalla y para la
     * puerta de escritura, para que no puedan discrepar.
     */
    fun blockedLineIndices(
        operations: List<LedgerOperation>,
        duplicateMatches: Map<Int, LedgerDuplicateMatch>,
        deterministicBlockedLineIndices: Set<Int>,
    ): Set<Int> = LinkedHashSet<Int>(deterministicBlockedLineIndices).apply {
        addAll(blockedLineIndices(operations, duplicateMatches))
    }
}
