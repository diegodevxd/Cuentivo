package com.cuentivo.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [OperationEntity::class, CustomerEntity::class, ProductEntity::class, SaleEntity::class, SaleItemEntity::class, PaymentEntity::class, PaymentAllocationEntity::class, AccountMovementEntity::class, BusinessEntity::class, BusinessMemberEntity::class], version = 1, exportSchema = true)
abstract class CuentivoDatabase : RoomDatabase() {
    abstract fun operationDao(): OperationDao
    abstract fun financialDao(): FinancialDao
}
