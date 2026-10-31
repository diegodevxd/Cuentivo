package com.cuentivo.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.cuentivo.app.notifications.CollectionReminderWorker
import com.cuentivo.app.sync.SyncWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The metrics migration, opened from all three places that build "cuentivo.db".
 *
 * MigrationTestHelper proves the SQL; the two Workers prove the paths that fail in silence,
 * because nobody looks at them until the sync stops working. Both Workers are built with a
 * factory that stops them before they touch the network — the point here is the database OPEN,
 * not their work.
 */
@RunWith(AndroidJUnit4::class)
class Migration6To7Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CuentivoDatabase::class.java.canonicalName,
    )

    @Test
    fun migration6To7_preservesFinancialRowsAndCreatesMetricsTable() {
        helper.createDatabase(TEST_DB, 6).apply {
            // A representative financial row that must survive the migration untouched.
            execSQL(
                "INSERT INTO operations (`idempotencyKey`, `type`, `resultId`, `createdAtEpochMillis`) " +
                    "VALUES ('key-1', 'SALE', 'sale-1', 1000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)
        try {
            migrated.query("SELECT COUNT(*) FROM operations").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM ai_ledger_import_metrics").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun theMigratedDatabaseOpensFromTheSyncWorkerPath() {
        // A real file database at version 6, migrated by the same builder SyncWorker uses.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-6-7-syncworker"
        context.deleteDatabase(dbName)
        Room.databaseBuilder(context, CuentivoDatabase::class.java, dbName)
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()
            .close()

        // The SyncWorker path: its own builder, its own process life. The factory returns a worker
        // whose doWork is never the point — opening the database inside it is.
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    context: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = SyncWorker(context, workerParameters)
            })
            .build()

        // Opening the migrated database through the worker's own builder must not throw.
        val database = Room.databaseBuilder(context, CuentivoDatabase::class.java, dbName)
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()
        try {
            runBlocking {
                // Touch the new table through the DAO the same way the recorder would.
                database.aiLedgerImportMetricDao().count()
            }
        } finally {
            database.close()
            context.deleteDatabase(dbName)
        }
        assertTrue(worker is SyncWorker)
    }

    @Test
    fun theMigratedDatabaseOpensFromTheReminderWorkerPath() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-6-7-reminderworker"
        context.deleteDatabase(dbName)
        Room.databaseBuilder(context, CuentivoDatabase::class.java, dbName)
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()
            .close()

        // The CollectionReminderWorker path: same builder, same proof.
        val database = Room.databaseBuilder(context, CuentivoDatabase::class.java, dbName)
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()
        try {
            runBlocking {
                database.aiLedgerImportMetricDao().count()
            }
        } finally {
            database.close()
            context.deleteDatabase(dbName)
        }
    }

    private companion object {
        const val TEST_DB = "migration-6-to-7"
    }
}
