package com.cuentivo.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiDailyMessageDaoTest {

    private lateinit var database: CuentivoDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun message(
        ownerUserId: String,
        dayIso: String,
        kind: String = "USER_TEXT",
        textContent: String? = "hola",
    ) = AiDailyMessageEntity(
        ownerUserId = ownerUserId,
        dayIso = dayIso,
        kind = kind,
        textContent = textContent,
        createdAtEpochMillis = 0L,
    )

    @Test
    fun messagesForDay_isScopedByOwnerAndDayAndOrderedById() = runBlocking {
        database.aiDailyMessageDao().insert(message("u1", "2026-08-23", textContent = "primero"))
        database.aiDailyMessageDao().insert(message("u2", "2026-08-23", textContent = "ajeno"))
        database.aiDailyMessageDao().insert(message("u1", "2026-08-24", textContent = "otro dia"))
        database.aiDailyMessageDao().insert(message("u1", "2026-08-23", textContent = "segundo"))

        val messages = database.aiDailyMessageDao().messagesForDay("u1", "2026-08-23")

        assertEquals(listOf("primero", "segundo"), messages.map { it.textContent })
        assertTrue(messages.zipWithNext().all { (a, b) -> a.id < b.id })
    }

    @Test
    fun deleteDay_removesOnlyTheTargetDay() = runBlocking {
        database.aiDailyMessageDao().insert(message("u1", "2026-08-23"))
        database.aiDailyMessageDao().insert(message("u1", "2026-08-24"))
        database.aiDailyMessageDao().insert(message("u2", "2026-08-23"))

        database.aiDailyMessageDao().deleteDay("u1", "2026-08-23")

        assertTrue(database.aiDailyMessageDao().messagesForDay("u1", "2026-08-23").isEmpty())
        assertEquals(1, database.aiDailyMessageDao().messagesForDay("u1", "2026-08-24").size)
        assertEquals(1, database.aiDailyMessageDao().messagesForDay("u2", "2026-08-23").size)
    }

    @Test
    fun deleteExpiredDays_keepsCutoffAndRemovesOlder() = runBlocking {
        database.aiDailyMessageDao().insert(message("u1", "2026-07-24")) // older -> deleted
        database.aiDailyMessageDao().insert(message("u1", "2026-07-25")) // cutoff -> kept
        database.aiDailyMessageDao().insert(message("u1", "2026-08-23")) // today -> kept
        database.aiDailyMessageDao().insert(message("u2", "2026-07-01")) // other owner untouched

        database.aiDailyMessageDao().deleteExpiredDays("u1", "2026-07-25")

        assertTrue(database.aiDailyMessageDao().messagesForDay("u1", "2026-07-24").isEmpty())
        assertEquals(1, database.aiDailyMessageDao().messagesForDay("u1", "2026-07-25").size)
        assertEquals(1, database.aiDailyMessageDao().messagesForDay("u1", "2026-08-23").size)
        assertEquals(1, database.aiDailyMessageDao().messagesForDay("u2", "2026-07-01").size)
    }

    @Test
    fun chatOperations_neverTouchFinancialRows() = runBlocking {
        val businessId = "b1"
        val customer = CustomerEntity(
            id = "c1",
            businessId = businessId,
            name = "María",
            normalizedName = "maria",
            phone = null,
            email = null,
            address = null,
            notes = null,
            creditLimitCents = null,
            isActive = true,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            deletedAtEpochMillis = null,
        )
        database.customerDao().insert(customer)
        database.aiDailyMessageDao().insert(message("u1", "2026-08-23"))

        database.aiDailyMessageDao().deleteDay("u1", "2026-08-23")
        database.aiDailyMessageDao().deleteExpiredDays("u1", "2026-08-23")

        val customers = database.customerDao().allForBackfill(businessId)
        assertEquals(listOf("María"), customers.map { it.name })
    }
}