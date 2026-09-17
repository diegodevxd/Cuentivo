package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation

/** Cuándo vale una confirmación de objeción y cuándo caduca por cambios posteriores. */
object LedgerObjectionConfirmation {
    fun fingerprint(op: LedgerOperation): String = listOf(
        field(op.customerId.orEmpty()),
        field(CustomerNameNormalizer.key(op.customerName.orEmpty())),
        field(op.amountCents.orEmpty()),
        field(CustomerNameNormalizer.key(op.description.orEmpty())),
    ).joinToString(separator = ":")

    fun isConfirmed(op: LedgerOperation): Boolean =
        op.isObjectionConfirmed && op.objectionConfirmedFingerprint == fingerprint(op)

    fun confirming(op: LedgerOperation): LedgerOperation = op.copy(
        isObjectionConfirmed = true,
        objectionConfirmedFingerprint = fingerprint(op),
    )

    private fun field(value: String): String = "${value.length}:$value"
}
