package com.cuentivo.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration5To6Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CuentivoDatabase::class.java.canonicalName,
    )

    @Test
    fun migration5To6_preservesFinancialRowsAndCreatesChatTable() {
        helper.createDatabase(TEST_DB, 5).apply {
            // A representative financial row that must survive the migration untouched.
            execSQL(
                "INSERT INTO operations (`idempotencyKey`, `type`, `resultId`, `createdAtEpochMillis`) " +
                    "VALUES ('key-1', 'SALE', 'sale-1', 1000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)
        try {
            migrated.query("SELECT COUNT(*) FROM operations").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM ai_daily_messages").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_ai_daily_messages_ownerUserId_dayIso_id'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-5-to-6"
    }
}