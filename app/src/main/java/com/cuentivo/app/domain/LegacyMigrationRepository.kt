package com.cuentivo.app.domain

import androidx.room.withTransaction
import com.cuentivo.app.data.local.CuentivoDatabase

data class LegacyMigrationExecutionReport(val customers: Int, val sales: Int, val saleItems: Int, val payments: Int, val accountMovements: Int)

class LegacyMigrationRepository(private val database: CuentivoDatabase) {
    suspend fun execute(plan: LegacyMigrationPlan): LegacyMigrationExecutionReport {
        require(plan.rejected.isEmpty()) { "Resolve rejected legacy movements before importing." }
        database.withTransaction {
            val customerDao = database.customerDao()
            val financialDao = database.financialDao()
            plan.customers.forEach { customerDao.insert(it) }
            plan.sales.forEach { financialDao.insertSale(it) }
            plan.saleItems.forEach { financialDao.insertSaleItems(listOf(it)) }
            plan.payments.forEach { financialDao.insertPayment(it) }
            plan.accountMovements.forEach { financialDao.insertMovement(it) }
        }
        return LegacyMigrationExecutionReport(plan.customers.size, plan.sales.size, plan.saleItems.size, plan.payments.size, plan.accountMovements.size)
    }
}
