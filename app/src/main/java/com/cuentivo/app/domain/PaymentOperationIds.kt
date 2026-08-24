package com.cuentivo.app.domain

import java.nio.charset.StandardCharsets
import java.util.UUID

object PaymentOperationIds {
    fun payment(idempotencyKey: String): String = child(idempotencyKey, "payment", "root")
    fun movement(idempotencyKey: String): String = child(idempotencyKey, "movement", "root")
    fun allocation(idempotencyKey: String, saleId: String): String = child(idempotencyKey, "allocation", saleId)

    private fun child(key: String, kind: String, suffix: String): String {
        require(key.isNotBlank()) { "Idempotency key must not be blank." }
        return UUID.nameUUIDFromBytes("$key:$kind:$suffix".toByteArray(StandardCharsets.UTF_8)).toString()
    }
}
