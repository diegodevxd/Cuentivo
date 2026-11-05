package com.cuentivo.app.domain

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity

data class LegacyMovimiento(val id: Long, val cliente: String, val tipo: String, val montoCents: Long, val descripcion: String?, val fechaEpochDay: Long, val estado: String?)
data class LegacyMigrationRejection(val legacyMovementId: Long, val reason: String)
data class LegacyMigrationPlan(val customers: List<CustomerEntity>, val sales: List<SaleEntity>, val saleItems: List<SaleItemEntity>, val payments: List<PaymentEntity>, val accountMovements: List<AccountMovementEntity>, val rejected: List<LegacyMigrationRejection>, val acceptedMovements: Int)

object LegacyMigrationPlanner {
    fun plan(businessId: String, ownerUserId: String, movements: List<LegacyMovimiento>): LegacyMigrationPlan {
        require(businessId.isNotBlank() && ownerUserId.isNotBlank())
        val customersByKey = linkedMapOf<String, CustomerEntity>()
        val sales = mutableListOf<SaleEntity>()
        val saleItems = mutableListOf<SaleItemEntity>()
        val payments = mutableListOf<PaymentEntity>()
        val accountMovements = mutableListOf<AccountMovementEntity>()
        val rejected = mutableListOf<LegacyMigrationRejection>()
        var accepted = 0
        movements.forEach { source ->
            val customerName = source.cliente.trim()
            if (customerName.isBlank()) { rejected += LegacyMigrationRejection(source.id, "Cliente vac?o."); return@forEach }
            if (source.montoCents <= 0) { rejected += LegacyMigrationRejection(source.id, "Monto no positivo."); return@forEach }
            val normalizedName = CustomerNameNormalizer.key(customerName)
            val customer = customersByKey.getOrPut(normalizedName) { CustomerEntity("legacy-customer-${source.id}", businessId, customerName, normalizedName, null, null, null, null, null, true, source.fechaEpochDay * 86_400_000L, source.fechaEpochDay * 86_400_000L, null) }
            accepted += 1
            val baseId = "legacy-movimiento-${source.id}"
            val occurredAt = source.fechaEpochDay * 86_400_000L
            when (source.tipo.lowercase()) {
                "pedido" -> {
                    val saleId = baseId
                    sales += SaleEntity(saleId, businessId, customer.id, ownerUserId, source.id.toString(), source.fechaEpochDay, null, source.montoCents, 0, source.montoCents, false, source.descripcion, occurredAt, occurredAt)
                    saleItems += SaleItemEntity("$saleId-item", saleId, null, source.descripcion?.takeIf { it.isNotBlank() } ?: "Pedido migrado", 1, source.montoCents, 0, source.montoCents)
                    accountMovements += AccountMovementEntity("$saleId-charge", businessId, customer.id, saleId, null, "CHARGE", source.montoCents, "DEBIT", occurredAt, ownerUserId, null, source.descripcion)
                    if (source.estado?.lowercase() == "pagado") {
                        val paymentId = "$saleId-online-payment"
                        payments += PaymentEntity(paymentId, businessId, customer.id, source.montoCents, "LEGACY_ONLINE", source.fechaEpochDay, null, source.descripcion, ownerUserId, occurredAt, null)
                        accountMovements += AccountMovementEntity("$paymentId-movement", businessId, customer.id, saleId, paymentId, "PAYMENT", source.montoCents, "CREDIT", occurredAt, ownerUserId, null, "Pedido web ya pagado")
                    }
                }
                "pago" -> {
                    val paymentId = baseId
                    payments += PaymentEntity(paymentId, businessId, customer.id, source.montoCents, "LEGACY_CASH", source.fechaEpochDay, null, source.descripcion, ownerUserId, occurredAt, null)
                    accountMovements += AccountMovementEntity("$paymentId-movement", businessId, customer.id, null, paymentId, "PAYMENT", source.montoCents, "CREDIT", occurredAt, ownerUserId, null, source.descripcion)
                }
                else -> { rejected += LegacyMigrationRejection(source.id, "Tipo no soportado: ${source.tipo}"); accepted -= 1 }
            }
        }
        return LegacyMigrationPlan(customersByKey.values.toList(), sales, saleItems, payments, accountMovements, rejected, accepted)
    }
}
