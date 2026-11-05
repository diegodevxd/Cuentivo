package com.cuentivo.app.ai.ledger

/**
 * Lo que la tarjeta del Ledger de MexaLM tiene que decir de la página.
 *
 * Es una decisión, y por eso vive aquí y no dentro del Composable: la prueba la puede invocar sin
 * Android, y la tarjeta sólo la dibuja.
 */
enum class MexalmCrosscheckOutcome {
    /** Los dos lados dicen lo mismo. */
    AGREED,

    /** Hay renglones en los que los dos lados dicen cosas distintas. */
    DISAGREEMENTS,

    /**
     * El Ledger NO leyó la página: no hay veredicto renglón por renglón.
     *
     * Es el caso que salió en el teléfono: la libreta de una clienta, doce renglones, y cada uno
     * con la insignia "Ledger: no lo vio". Lo que había pasado es que el Ledger no pudo
     * certificar la página y lo había dicho (`BLOCKED`). Eso no es "el Ledger no vio doce
     * renglones": es "el Ledger no leyó la página", y se dice UNA vez.
     */
    COULD_NOT_READ,

    /** No se puede afirmar que estén de acuerdo, y tampoco hay un desacuerdo que señalar. */
    NO_VERDICT,
}

/**
 * Por qué el Ledger no leyó la página.
 *
 * Son los dos status con los que el Ledger responde que no certificó la página. El texto que ve
 * la persona sale de aquí, en lenguaje sencillo; el status crudo sigue en el modelo para quien
 * tenga quediagnosticar.
 */
enum class MexalmCrosscheckUnreadReason {
    /** El Ledger se detuvo: no pudo certificar la página. */
    BLOCKED,

    /** El Ledger leyó la página pero la dejó pendiente de revisión. */
    REVIEW_REQUIRED,
}

/**
 * El veredicto de la verificación cruzada, ya interpretado.
 *
 * [visibleDisagreements] son las diferencias que SÍ se muestran. Puede ser menos que
 * [MexalmCrosscheckPayload.disagreements]: cuando el Ledger no leyó la página, que no haya visto
 * un renglón no es una diferencia de ESE renglón, así que esas no se muestran. Lo que el Ledger
 * sí leyó —el monto, el cliente, el tipo— sí se muestra, porque ése sí es un renglón con una
 * diferencia de valor.
 */
data class MexalmCrosscheckVerdict(
    val outcome: MexalmCrosscheckOutcome,
    val visibleDisagreements: List<MexalmCrosscheckDisagreement>,
    /** El status del Ledger, verbatim. */
    val ledgerStatus: String,
    /** Por qué no leyó la página, o `null` si la leyó. */
    val unreadReason: MexalmCrosscheckUnreadReason?,
    /**
     * Cuántos renglones confirmó el Ledger de verdad.
     *
     * No es lo mismo que [MexalmCrosscheckOutcome.AGREED]: "no hubo desacuerdos" también pasa
     * cuando el Ledger no tuvo opinión de nada. Este número es el de los renglones que las dos
     * lecturas dijeron IGUALES, y es el que explica por qué un renglón dejó de estar "por revisar".
     */
    val confirmedRowCount: Int = 0,
) {
    /**
     * Cuántos RENGLONES discrepan, no cuántas diferencias: un renglón puede diferir en el monto y
     * en el cliente, y "leyó 3 renglones distintos" con dos insignias en el mismo renglón se
     * contradice.
     */
    val disagreeingRowCount: Int
        get() = visibleDisagreements.map { it.lineIndex }.toSet().size
}

/**
 * Interpreta el bloque del servidor y dice qué se muestra.
 *
 * El servidor manda el bloque tal cual lo devolvió el Ledger: `agreed`, `disagreements`,
 * `ledgerStatus`. Aquí se decide qué significa cada combinación, y hay tres reglas:
 *
 * 1. **El Ledger no leyó la página** (`BLOCKED`, `REVIEW_REQUIRED`): se dice UNA vez, y las
 *    diferencias de PRESENCIA no se muestran. No son la diferencia de un renglón: son la página
 *    entera sin certificar.
 * 2. **Los dos lados dicen lo mismo** (`agreed`): se dice que están de acuerdo.
 * 3. **Hay renglones que difieren**: se dice cuántos, y las insignias van en su renglón.
 *
 * Los status de la regla 1 son los mismos que usa el servidor (`functions/src/ai/
 * mexalmLedgerClient.ts`): si uno cambia, cambia el otro.
 */
object MexalmCrosscheckVerdictCalculator {

    private const val LEDGER_STATUS_BLOCKED = "BLOCKED"
    private const val LEDGER_STATUS_REVIEW_REQUIRED = "REVIEW_REQUIRED"

    /**
     * Por qué el Ledger no leyó la página, o `null` si sí la leyó.
     */
    fun unreadReason(ledgerStatus: String): MexalmCrosscheckUnreadReason? = when (ledgerStatus) {
        LEDGER_STATUS_BLOCKED -> MexalmCrosscheckUnreadReason.BLOCKED
        LEDGER_STATUS_REVIEW_REQUIRED -> MexalmCrosscheckUnreadReason.REVIEW_REQUIRED
        else -> null
    }

    /**
     * El veredicto de un bloque del servidor.
     */
    fun calculate(payload: MexalmCrosscheckPayload): MexalmCrosscheckVerdict {
        val reason = unreadReason(payload.ledgerStatus)
        val visible = if (reason == null) {
            payload.disagreements
        } else {
            payload.disagreements.filter { it.field.isValueDifference }
        }
        val outcome = when {
            // El Ledger no leyó la página: eso se dice una vez, y no renglón por renglón.
            reason != null -> MexalmCrosscheckOutcome.COULD_NOT_READ
            payload.agreed -> MexalmCrosscheckOutcome.AGREED
            visible.isNotEmpty() -> MexalmCrosscheckOutcome.DISAGREEMENTS
            else -> MexalmCrosscheckOutcome.NO_VERDICT
        }
        return MexalmCrosscheckVerdict(
            outcome = outcome,
            visibleDisagreements = visible,
            ledgerStatus = payload.ledgerStatus,
            unreadReason = reason,
            // Los renglones confirmados sólo existen cuando el Ledger dio veredicto: sin veredicto
            // no hay nada confirmado, por mucho que no haya desacuerdos.
            confirmedRowCount = MexalmCrosscheckConfirmation.confirmedLineIndices(payload).size,
        )
    }
}
