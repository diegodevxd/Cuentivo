package com.cuentivo.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 7 to 8 test: adding [parserExcludedCount] column to `ai_ledger_import_metrics`.
 *
 * Validates that existing metric rows survive with default 0, and that a database configured
 * with [CUENTIVO_MIGRATIONS] opens cleanly and allows DAO operations.
 */
@RunWith(AndroidJUnit4::class)
class Migration7To8Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CuentivoDatabase::class.java,
    )

    @Test
    fun migration7To8_preservesExistingMetricsAndAddsParserExcludedCountColumn() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                "INSERT INTO ai_ledger_import_metrics (" +
                    "`importId`, `finishedAtEpochHour`, `status`, `detectedCount`, `readyUntouchedCount`, " +
                    "`correctedCount`, `discardedCount`, `unreadableCount`, `duplicateSuggestedCount`, " +
                    "`customerCorrections`, `productCorrections`, `amountCorrections`, `dateCorrections`, " +
                    "`kindCorrections`, `geminiCalls`, `geminiDurationMillis`, `contractVersion`, " +
                    "`highConfidenceFields`, `highConfidenceCorrected`, `midConfidenceFields`, " +
                    "`midConfidenceCorrected`, `lowConfidenceFields`, `lowConfidenceCorrected`) " +
                    "VALUES ('import-v7', 1000, 'REGISTERED', 5, 4, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1, 2000, 4, 0, 0, 0, 0, 0, 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
        try {
            migrated.query("SELECT importId, detectedCount, parserExcludedCount FROM ai_ledger_import_metrics WHERE importId = 'import-v7'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("import-v7", cursor.getString(0))
                assertEquals(5, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun databaseWithCuentivoMigrationsArray_opensCleanlyAndAllowsDaoQueries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-7-8-direct-open"
        context.deleteDatabase(dbName)
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
        const val TEST_DB = "migration-7-to-8"
    }
}
