package com.cuentivo.app.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.CUENTIVO_MIGRATIONS
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The round trip on a real database file, which is the only thing that proves a backup is a backup.
 * `BackupCryptoTest` covers the envelope; this covers that what comes back is the ledger.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: CuentivoDatabase

    @Before fun setUp() {
        context.deleteDatabase(DatabaseBackup.DATABASE_NAME)
        database = open()
    }

    @After fun tearDown() {
        if (database.isOpen) database.close()
        context.deleteDatabase(DatabaseBackup.DATABASE_NAME)
    }

    @Test fun a_restored_backup_brings_back_the_customer_and_their_balance() = runBlocking {
        seedLedger()
        assertEquals(40_000, database.financialDao().customerBalanceCents("business-1", "customer-1"))

        val backup = DatabaseBackup.export(context, database, PASSWORD.toCharArray())

        // Everything gone, the way a lost phone leaves it.
        database.close()
        context.deleteDatabase(DatabaseBackup.DATABASE_NAME)
        database = open()
        assertEquals(0, database.financialDao().customerBalanceCents("business-1", "customer-1"))

        DatabaseBackup.restore(context, database, backup, PASSWORD.toCharArray())
        database = open()

        assertNotNull(database.customerDao().byId("customer-1"))
        assertEquals(40_000, database.financialDao().customerBalanceCents("business-1", "customer-1"))
    }

    /**
     * The safety property that matters most: a wrong password must fail *before* anything is
     * destroyed. Restoring is the one irreversible button in the app, and a typo in it cannot be
     * allowed to wipe the ledger it was meant to protect.
     */
    @Test fun a_wrong_password_leaves_the_current_ledger_untouched() = runBlocking {
        seedLedger()
        val backup = DatabaseBackup.export(context, database, PASSWORD.toCharArray())

        val failure = runCatching {
            DatabaseBackup.restore(context, database, backup, "no es la clave".toCharArray())
        }.exceptionOrNull()

        assertEquals(BackupFailure.WRONG_PASSWORD_OR_DAMAGED, (failure as BackupException).failure)
        assertEquals(40_000, database.financialDao().customerBalanceCents("business-1", "customer-1"))
    }

    /**
     * Recent writes sit in the `-wal` sidecar until SQLite folds them in. Without the checkpoint in
     * `export`, this sale would be missing from the backup and nobody would notice until they
     * needed it.
     */
    @Test fun a_sale_registered_moments_ago_is_inside_the_backup() = runBlocking {
        seedLedger()
        database.financialDao().insertMovement(
            AccountMovementEntity("movement-2", "business-1", "customer-1", null, null, "PAYMENT", 15_000, "CREDIT", 2, "owner-1", null, null)
        )

        val backup = DatabaseBackup.export(context, database, PASSWORD.toCharArray())
        database.close()
        context.deleteDatabase(DatabaseBackup.DATABASE_NAME)
        database = open()
        DatabaseBackup.restore(context, database, backup, PASSWORD.toCharArray())
        database = open()

        assertEquals(25_000, database.financialDao().customerBalanceCents("business-1", "customer-1"))
    }

    private suspend fun seedLedger() {
        database.customerDao().insert(
            CustomerEntity("customer-1", "business-1", "Ana", "ana", null, null, null, null, null, true, 1, 1, null)
        )
        database.financialDao().insertMovement(
            AccountMovementEntity("movement-1", "business-1", "customer-1", null, null, "CHARGE", 40_000, "DEBIT", 1, "owner-1", null, null)
        )
    }

    private fun open(): CuentivoDatabase =
        Room.databaseBuilder(context, CuentivoDatabase::class.java, DatabaseBackup.DATABASE_NAME)
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()

    private companion object {
        const val PASSWORD = "clave de prueba"
    }
}
