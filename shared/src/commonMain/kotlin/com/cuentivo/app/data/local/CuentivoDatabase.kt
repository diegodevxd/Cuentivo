package com.cuentivo.app.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
        OperationEntity::class,
        CustomerEntity::class,
        ProductEntity::class,
        SaleEntity::class,
        SaleItemEntity::class,
        PaymentEntity::class,
        PaymentAllocationEntity::class,
        AccountMovementEntity::class,
        BusinessEntity::class,
        BusinessMemberEntity::class,
        AuditLogEntity::class,
        SyncOperationEntity::class,
        SyncStateEntity::class,
        AiDailyMessageEntity::class,
        AiLedgerImportMetricEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@ConstructedBy(CuentivoDatabaseConstructor::class)
abstract class CuentivoDatabase : RoomDatabase() {
    abstract fun operationDao(): OperationDao
    abstract fun financialDao(): FinancialDao
    abstract fun customerDao(): CustomerDao
    abstract fun productDao(): ProductDao
    abstract fun auditDao(): AuditDao
    abstract fun businessDao(): BusinessDao
    abstract fun syncOperationDao(): SyncOperationDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun aiDailyMessageDao(): AiDailyMessageDao
    abstract fun aiLedgerImportMetricDao(): AiLedgerImportMetricDao
}

/**
 * El constructor que Room genera para cada plataforma (KSP por target).
 *
 * Android e iOS no abren la base igual —Android usa un `Builder` con las migraciones; iOS arranca
 * de cero con `BundledSQLiteDriver`—, así que esto queda como `expect` y cada target recibe su
 * `actual` generado por Room.
 */
expect object CuentivoDatabaseConstructor : RoomDatabaseConstructor<CuentivoDatabase> {
    override fun initialize(): CuentivoDatabase
}
