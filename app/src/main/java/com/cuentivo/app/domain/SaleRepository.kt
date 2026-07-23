package com.cuentivo.app.domain

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.FinancialDao
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity
import com.cuentivo.app.data.local.OperationEntity

class SaleRepository(private val financialDao: FinancialDao) {
    suspend fun create(idempotencyKey: String, sale: SaleEntity, items: List<SaleItemEntity>, charge: AccountMovementEntity): String {
        require(items.isNotEmpty()) { "A sale needs at least one item." }
        require(charge.type == "CHARGE" && charge.direction == "DEBIT")
        return financialDao.createSaleIdempotently(
            operation = OperationEntity(idempotencyKey, "SALE", sale.id, sale.createdAtEpochMillis),
            sale = sale,
            items = items,
            charge = charge,
        )
    }
}
