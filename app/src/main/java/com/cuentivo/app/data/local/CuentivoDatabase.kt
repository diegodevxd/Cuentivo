package com.cuentivo.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [OperationEntity::class, CustomerEntity::class, ProductEntity::class, SaleEntity::class, SaleItemEntity::class, PaymentEntity::class, PaymentAllocationEntity::class, AccountMovementEntity::class, BusinessEntity::class, BusinessMemberEntity::class, AuditLogEntity::class, SyncOperationEntity::class], version = 3, exportSchema = true)
abstract class CuentivoDatabase : RoomDatabase() {
    abstract fun operationDao(): OperationDao
    abstract fun financialDao(): FinancialDao
    abstract fun customerDao(): CustomerDao
    abstract fun productDao(): ProductDao
    abstract fun auditDao(): AuditDao
    abstract fun businessDao(): BusinessDao
    abstract fun syncOperationDao(): SyncOperationDao
}
