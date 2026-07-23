package com.cuentivo.app.domain

import androidx.room.withTransaction
import com.cuentivo.app.data.local.AuditLogEntity
import com.cuentivo.app.data.local.CuentivoDatabase

data class LegacyMigrationExecutionReport(val customers: Int, val sales: Int, val saleItems: Int, val payments: Int, val accountMovements: Int)

class LegacyMigrationRepository(private val database: CuentivoDatabase) {
    suspend fun execute(plan: LegacyMigrationPlan): LegacyMigrationExecutionReport {
        require(plan.rejected.isEmpty()) { "Resolve rejected legacy movements before importing." }
        require(plan.acceptedMovements > 0) { "A migration plan must contain accepted movements." }
        database.withTransaction {
            val customerDao = database.customerDao()
            val financialDao = database.financialDao()
            plan.customers.forEach { customerDao.insert(it) }
            plan.sales.forEach { financialDao.insertSale(it) }
            plan.saleItems.forEach { financialDao.insertSaleItems(listOf(it)) }
            plan.payments.forEach { financialDao.insertPayment(it) }
            plan.accountMovements.forEach { financialDao.insertMovement(it) }
            val firstMovement = plan.accountMovements.first()
            database.auditDao().insert(AuditLogEntity(
                id = "migration-${firstMovement.id}", businessId = firstMovement.businessId, entityType = "MIGRATION", entityId = firstMovement.id,
                action = "MIGRATION_EXECUTED", userId = firstMovement.createdByUserId, timestampEpochMillis = firstMovement.occurredAtEpochMillis,
                summary = "accepted=${plan.acceptedMovements}; sales=${plan.sales.size}; payments=${plan.payments.size}",
            ))
        }
        return LegacyMigrationExecutionReport(plan.customers.size, plan.sales.size, plan.saleItems.size, plan.payments.size, plan.accountMovements.size)
    }
}
