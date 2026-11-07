package com.cuentivo.app.data.local

import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClearAllRowsTest {

    private val database = Room.inMemoryDatabaseBuilder<CuentivoDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()

    @AfterTest
    fun cleanUp() {
        runBlocking { database.close() }
    }

    @Test
    fun `clear all rows empties the business but keeps the room master row`() = runTest {
        database.businessDao().insert(
            BusinessEntity(
                id = "business-1",
                name = "Mi negocio",
                ownerUserId = "owner-1",
                currency = "MXN",
                timezone = "America/Mexico_City",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
            ),
        )

        database.clearAllRows()

        assertNull(database.businessDao().first())
        val masterRows = database.useWriterConnection { transactor ->
            transactor.usePrepared("SELECT COUNT(*) FROM room_master_table") { statement ->
                if (statement.step()) statement.getLong(0) else 0L
            }
        }
        assertEquals(1L, masterRows)
    }
}
