package com.cuentivo.app.domain

/**
 * Lo que dice la imagen del estado de cuenta, ya resuelto: **qué se dibuja**, sin saber cómo.
 *
 * Kotlin puro, sin `Context` ni `R.string`, por la misma razón que el resto de `domain/`: así se
 * prueba en `testDebugUnitTest` que el recorte, el conteo de lo que falta y el saldo son correctos
 * antes de que exista un solo píxel. El dibujo vive en la capa de UI.
 */
data class StatementImageContent(
    /** El nombre del negocio si está configurado; si no, "Estado de cuenta". */
    val title: String,
    val customerName: String,
    /** Los últimos [StatementImage.MAX_MOVEMENTS] movimientos, en el orden en que venían. */
    val movements: List<Movement>,
    /** "y N movimientos anteriores" cuando se recortaron, o `null`. */
    val moreLine: String?,
    /** "Saldo pendiente", o el agradecimiento cuando no debe nada. */
    val balanceLabel: String,
    /** El saldo **real**, no el de los diez que se ven. `null` si no debe nada. */
    val balanceText: String?,
    /** El sello de Cuentivo, sólo para quien no paga. */
    val showsStamp: Boolean,
    /** La fecha que identifica este estado de cuenta; la decide el llamador, nunca el reloj. */
    val statementDateLine: String,
) {
    data class Movement(val line: String, val detail: String?)
}

object StatementImage {

    /** Cuántos movimientos caben. Lo que sobra se cuenta en una línea, no se esconde. */
    const val MAX_MOVEMENTS = 10

    /**
     * Arma el contenido de la imagen de **un** cliente.
     *
     * Recibe los movimientos de ese cliente y nada más: igual que [DebtorShareText.forCustomer], no
     * existe forma de que le llegue el nombre o el saldo de otro. **Una imagen se reenvía sin pensar**,
     * así que esa invariante pesa aquí más que en el texto.
     *
     * El saldo es **siempre el real**, aunque los movimientos que se dibujen sean sólo los últimos
     * diez: recortar la lista no puede recortar la deuda.
     */
    fun build(
        businessName: String?,
        customerName: String,
        balanceCents: Long,
        entries: List<StatementEntry>,
        tier: SubscriptionTier,
        statementDateIso: String,
    ): StatementImageContent {
        val shown = entries.takeLast(MAX_MOVEMENTS)
        val hidden = entries.size - shown.size
        val settled = balanceCents <= 0
        return StatementImageContent(
            title = if (BusinessName.isConfigured(businessName)) {
                businessName?.trim().orEmpty()
            } else {
                TITLE_WITHOUT_BUSINESS_NAME
            },
            customerName = customerName,
            movements = shown.map {
                StatementImageContent.Movement(
                    line = StatementLineFormat.movementLine(it),
                    detail = StatementLineFormat.detailOrNull(it),
                )
            },
            moreLine = when {
                hidden <= 0 -> null
                hidden == 1 -> "y 1 movimiento anterior"
                else -> "y $hidden movimientos anteriores"
            },
            balanceLabel = if (settled) SETTLED_LABEL else BALANCE_LABEL,
            balanceText = if (settled) null else Money.format(balanceCents),
            // La misma regla que el pie del texto, y por la misma razón: un plan de pago que se añada
            // mañana no debe llevar marca por olvido.
            showsStamp = !tier.includesProBenefits,
            statementDateLine = "Estado de cuenta al ${StatementLineFormat.displayDate(statementDateIso)}",
        )
    }

    internal const val TITLE_WITHOUT_BUSINESS_NAME = "Estado de cuenta"
    internal const val BALANCE_LABEL = "Saldo pendiente"
    internal const val SETTLED_LABEL = "Tu cuenta está al corriente"
}
