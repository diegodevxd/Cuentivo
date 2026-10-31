package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation

/**
 * Cuándo vale una confirmación de objeción y cuándo caduca por cambios posteriores.
 *
 * La huella cubre **todas** las señales que deciden si un renglón objeta o casa con un duplicado:
 * cliente, monto, descripción, **fecha y tipo** (v44). Antes faltaban fecha y tipo, así que tras
 * confirmar "Sí, es correcto" / "Sí, es otra venta" bastaba cambiar sólo la fecha (a otro año
 * equivocado) o el tipo para que la confirmación siguiera valiendo y el renglón pasara sin revisar.
 * Con la fecha y el tipo dentro, cualquier cambio en ellos **anula** la confirmación y el renglón
 * vuelve a objetar.
 *
 * Los borradores guardados con la huella vieja (sin fecha ni tipo) no se rompen: al restaurar, la
 * huella guardada no coincide con la nueva, así que el renglón vuelve a pedir confirmación — que es
 * el lado seguro. No hace falta subir `LedgerReviewSchema`: los campos siguen viajando igual.
 */
object LedgerObjectionConfirmation {
    fun fingerprint(op: LedgerOperation): String = listOf(
        field(op.customerId.orEmpty()),
        field(CustomerNameNormalizer.key(op.customerName.orEmpty())),
        field(op.amountCents.orEmpty()),
        field(CustomerNameNormalizer.key(op.description.orEmpty())),
        // Fecha: año, mes y día, o vacío si el renglón no trae. Un cambio de fecha cambia la huella.
        field(op.date?.let { "${it.year}-${it.month}-${it.day}" }.orEmpty()),
        // Tipo: SALE / PAYMENT / PRIOR_DEBT, o vacío si el modelo no lo leyó.
        field(op.proposedKind?.name.orEmpty()),
    ).joinToString(separator = ":")

    fun isConfirmed(op: LedgerOperation): Boolean =
        op.isObjectionConfirmed && op.objectionConfirmedFingerprint == fingerprint(op)

    fun confirming(op: LedgerOperation): LedgerOperation = op.copy(
        isObjectionConfirmed = true,
        objectionConfirmedFingerprint = fingerprint(op),
    )

    private fun field(value: String): String = "${value.length}:$value"
}
