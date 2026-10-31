package com.cuentivo.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [OperationEntity::class, CustomerEntity::class, ProductEntity::class, SaleEntity::class, SaleItemEntity::class, PaymentEntity::class, PaymentAllocationEntity::class, AccountMovementEntity::class, BusinessEntity::class, BusinessMemberEntity::class, AuditLogEntity::class, SyncOperationEntity::class, SyncStateEntity::class, AiDailyMessageEntity::class, AiLedgerImportMetricEntity::class], version = 8, exportSchema = true)
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
 * Adds the [SyncStateEntity] table introduced with the remote pull.
 *
 * Additive only: no existing table is touched, so no financial row can be lost by running it.
 * Every builder for "cuentivo.db" must register this, otherwise opening an installed app throws
 * IllegalStateException — there is no fallbackToDestructiveMigration() anywhere in the project,
 * on purpose.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_state` (" +
                "`businessId` TEXT NOT NULL, " +
                "`lastPulledAtEpochMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`businessId`))"
        )
    }
}

/**
 * Customer names were incorrectly promoted to an identity by a unique SQLite index. The row itself
 * remains untouched; only the constraint changes, so IDs and financial history stay intact.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `index_customers_businessId_normalizedName`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_customers_businessId_normalizedName` " +
                "ON `customers` (`businessId`, `normalizedName`)"
        )
    }
}

/**
 * Adds the local daily AI chat table.
 *
 * Additive only: no existing table is touched, so no financial row can be lost by running it. The
 * table stores only non-financial conversation copy scoped by owner and device-local day; there is
 * no parent table, foreign key, proposal JSON, money or customer/product IDs.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_daily_messages` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`ownerUserId` TEXT NOT NULL, " +
                "`dayIso` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`textContent` TEXT, " +
                "`createdAtEpochMillis` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ai_daily_messages_ownerUserId_dayIso_id` " +
                "ON `ai_daily_messages` (`ownerUserId`, `dayIso`, `id`)"
        )
    }
}

/**
 * Adds the local AI ledger import metrics table.
 *
 * Additive only: no existing table is touched, so no financial row can be lost by running it.
 * The table holds counts per import — never names, amounts, ids or free text — and is never
 * synced: it stays on the phone it was written on.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_ledger_import_metrics` (" +
                "`importId` TEXT NOT NULL, " +
                "`finishedAtEpochHour` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`detectedCount` INTEGER NOT NULL, " +
                "`readyUntouchedCount` INTEGER NOT NULL, " +
                "`correctedCount` INTEGER NOT NULL, " +
                "`discardedCount` INTEGER NOT NULL, " +
                "`unreadableCount` INTEGER NOT NULL, " +
                "`duplicateSuggestedCount` INTEGER NOT NULL, " +
                "`customerCorrections` INTEGER NOT NULL, " +
                "`productCorrections` INTEGER NOT NULL, " +
                "`amountCorrections` INTEGER NOT NULL, " +
                "`dateCorrections` INTEGER NOT NULL, " +
                "`kindCorrections` INTEGER NOT NULL, " +
                "`geminiCalls` INTEGER NOT NULL, " +
                "`geminiDurationMillis` INTEGER NOT NULL, " +
                "`contractVersion` INTEGER NOT NULL, " +
                "`highConfidenceFields` INTEGER NOT NULL, " +
                "`highConfidenceCorrected` INTEGER NOT NULL, " +
                "`midConfidenceFields` INTEGER NOT NULL, " +
                "`midConfidenceCorrected` INTEGER NOT NULL, " +
                "`lowConfidenceFields` INTEGER NOT NULL, " +
                "`lowConfidenceCorrected` INTEGER NOT NULL, " +
                "PRIMARY KEY(`importId`))"
        )
    }
}

/**
 * Adds the [parserExcludedCount] column to the [AiLedgerImportMetricEntity] table.
 *
 * Additive only: adds a single integer column with DEFAULT 0, preserving existing rows and table integrity.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `ai_ledger_import_metrics` ADD COLUMN `parserExcludedCount` INTEGER NOT NULL DEFAULT 0")
    }
}

val CUENTIVO_MIGRATIONS = arrayOf(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
