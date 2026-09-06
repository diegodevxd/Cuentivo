package com.cuentivo.app.domain

import java.nio.charset.StandardCharsets
import java.util.UUID

object SaleOperationIds {
    fun sale(idempotencyKey: String): String = child(idempotencyKey, "sale", "root")
    fun saleItem(idempotencyKey: String, productId: String, index: Int): String =
        child(idempotencyKey, "sale_item", "$productId:$index")
    fun uncatalogedSaleItem(idempotencyKey: String, index: Int): String =
        child(idempotencyKey, "uncataloged_sale_item", index.toString())
    fun chargeMovement(idempotencyKey: String): String = child(idempotencyKey, "charge_movement", "root")
    fun payment(idempotencyKey: String): String = child(idempotencyKey, "payment", "root")
    fun paymentMovement(idempotencyKey: String): String = child(idempotencyKey, "payment_movement", "root")
    fun allocation(idempotencyKey: String, saleId: String): String = child(idempotencyKey, "allocation", saleId)

    private fun child(key: String, kind: String, suffix: String): String {
        require(key.isNotBlank()) { "Idempotency key must not be blank." }
        return UUID.nameUUIDFromBytes("$key:$kind:$suffix".toByteArray(StandardCharsets.UTF_8)).toString()
    }
}
