package com.cuentivo.app.domain

object PaymentOperationIds {
    fun payment(idempotencyKey: String): String = child(idempotencyKey, "payment", "root")
    fun movement(idempotencyKey: String): String = child(idempotencyKey, "movement", "root")
    fun allocation(idempotencyKey: String, saleId: String): String = child(idempotencyKey, "allocation", saleId)

    private fun child(key: String, kind: String, suffix: String): String {
        require(key.isNotBlank()) { "Idempotency key must not be blank." }
        return nameUuidV3("$key:$kind:$suffix")
    }
}
