package com.cuentivo.app.ai.ledger

/**
 * Lo que el acuerdo del Ledger de MexaLM resuelve, y lo que NO.
 *
 * Es una decisión, y por eso vive aquí y no dentro del Composable: la prueba la puede invocar sin
 * Android, y la pantalla sólo la dibuja.
 *
 * ## Lo que RESUELVE
 *
 * La duda de LECTURA de un renglón: lo que la lectura de la foto no estaba segura de haber
 * leído — el monto, el cliente o el tipo. Cuando el Ledger vuelve a interpretar la misma
 * transcripción y dice lo mismo en esos tres campos, la duda queda contestada por una segunda
 * lectura y el renglón deja de estar "por revisar".
 *
 * ## Lo que NO resuelve
 *
 * Todo lo que el Ledger no verifica:
 *
 * - el año distinto al de la hoja (el aviso "¿Los cambio a 2026?" se queda igual);
 * - una fecha faltante o rara;
 * - un cliente nuevo o no registrado;
 * - un posible duplicado;
 * - el saldo o los límites;
 * - cualquier otra regla de negocio.
 *
 * Eso no son dudas de lectura: son objeciones y reglas que viven en la app, se calculan con los
 * datos del negocio y no se tocan aquí. Una duda de FECHA tampoco se resuelve: el Ledger no
 * compara fechas, así que no sabe nada de ellas.
 *
 * ## Lo que el objeto NO hace
 *
 * No registra nada, no cambia un monto y no reescribe lo que dijo la lectura. Quita una marca de
 * duda y nada más: lo que se anota es exactamente lo que la lectura de la página dijo, y el dueño
 * sigue pulsando el botón de siempre.
 */
object MexalmCrosscheckConfirmation {

    /**
     * El único status del Ledger que significa que dio veredicto.
     *
     * Con `BLOCKED` o `REVIEW_REQUIRED` no leyó la página: no hay veredicto que haya confirmado
     * nada. Con cualquier otro status no se inventa un veredicto: sólo "OK" cuenta.
     */
    const val LEDGER_STATUS_OK = "OK"

    /**
     * Las dudas que son de LECTURA.
     *
     * Son las tres que el Ledger compara: monto, cliente y tipo. La fecha NO está, y a propósito:
     * el Ledger no compara fechas, así que un "no estoy seguro de la fecha" no lo puede resolver
     * nadie con esta lista.
     */
    private val READING_FIELDS: Set<UncertainLedgerField> = setOf(
        UncertainLedgerField.AMOUNT,
        UncertainLedgerField.CUSTOMER,
        UncertainLedgerField.KIND,
    )

    private val READING_UNREADABLE_FIELDS: Set<UnreadableLedgerField> = setOf(
        UnreadableLedgerField.AMOUNT,
        UnreadableLedgerField.CUSTOMER,
        UnreadableLedgerField.KIND,
    )

    /**
     * Los renglones que el Ledger confirmó, o el conjunto vacío si no hay bloque.
     *
     * Un bloque sin lista de confirmados significa "no hay renglones confirmados": un servidor más
     * viejo no manda la lista, y su ausencia nunca es "confirmó todos".
     */
    fun confirmedLineIndices(payload: MexalmCrosscheckPayload?): Set<Int> {
        if (payload == null) return emptySet()
        if (payload.ledgerStatus != LEDGER_STATUS_OK) return emptySet()
        return payload.confirmedLineIndices
    }

    /**
     * ¿El Ledger confirmó ESTE renglón?
     *
     * Las tres condiciones, y las tres hacen falta:
     *
     * 1. el Ledger dio veredicto (`OK`) y el renglón está en la lista de confirmados;
     * 2. no hay ningún desacuerdo reportado para ese renglón;
     * 3. el renglón existe en la página (un `lineIndex` de verdad, no el `-1` que significa "el
     *    Ledger no dijo de qué renglón habla").
     */
    fun isConfirmed(payload: MexalmCrosscheckPayload?, lineIndex: Int): Boolean {
        if (payload == null) return false
        if (lineIndex < 0) return false
        if (payload.ledgerStatus != LEDGER_STATUS_OK) return false
        if (payload.disagreeingLineIndices.contains(lineIndex)) return false
        return payload.confirmedLineIndices.contains(lineIndex)
    }

    /**
     * El renglón con las dudas de lectura que el Ledger resolvió.
     *
     * Devuelve el mismo renglón sin tocar cuando el Ledger no lo confirmó: sin bloque, con otro
     * status, con un desacuerdo en ese renglón, o si el renglón no estaba confirmado. El renglón
     * que no se puede resolver se queda como está — una duda que no se puede resolver no se
     * resuelve a medias.
     *
     * Lo que se quita es la marca de duda de los campos de lectura: el monto, el cliente y el
     * tipo. Lo que NO se toca: la fecha, el texto de la hoja, y todo lo que la app calcula aparte
     * (objeciones, duplicados, saldo).
     */
    fun resolveReadingDoubts(
        operation: LedgerOperation,
        payload: MexalmCrosscheckPayload?,
    ): LedgerOperation {
        if (!isConfirmed(payload, operation.lineIndex)) return operation
        val remainingUncertain = operation.uncertainFields.filterNot { it in READING_FIELDS }
        val remainingUnreadable = operation.unreadableFields.filterNot { it in READING_UNREADABLE_FIELDS }
        if (remainingUncertain.size == operation.uncertainFields.size &&
            remainingUnreadable.size == operation.unreadableFields.size
        ) {
            // No había ninguna duda de lectura que quitar: el renglón no cambia.
            return operation
        }
        return operation.copy(
            uncertainFields = remainingUncertain,
            unreadableFields = remainingUnreadable,
        )
    }
}
