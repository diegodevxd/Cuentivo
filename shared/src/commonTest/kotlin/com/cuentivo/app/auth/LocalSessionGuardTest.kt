package com.cuentivo.app.auth

import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.platform.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSessionGuardTest {

    private fun business(ownerUserId: String) = BusinessEntity(
        id = "business-1",
        name = "Mi negocio",
        ownerUserId = ownerUserId,
        currency = "MXN",
        timezone = "America/Mexico_City",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    @Test
    fun `a different last user wipes and reports it`() = runTest {
        val preferences = InMemoryKeyValueStore()
        preferences.edit { putString("lastUserId", "old-user") }
        var wipes = 0
        val guard = LocalSessionGuard(
            preferences = preferences,
            firstBusiness = { business("old-user") },
            clearAllTables = { wipes++ },
        )

        assertTrue(guard.adopt("new-user"))
        assertEquals(1, wipes)
        assertEquals("new-user", preferences.getString("lastUserId"))
    }

    @Test
    fun `the same last user does not wipe`() = runTest {
        val preferences = InMemoryKeyValueStore()
        preferences.edit { putString("lastUserId", "same-user") }
        var wipes = 0
        val guard = LocalSessionGuard(
            preferences = preferences,
            firstBusiness = { business("same-user") },
            clearAllTables = { wipes++ },
        )

        assertFalse(guard.adopt("same-user"))
        assertEquals(0, wipes)
    }

    @Test
    fun `without a last user a foreign business wipes`() = runTest {
        val preferences = InMemoryKeyValueStore()
        var wipes = 0
        val guard = LocalSessionGuard(
            preferences = preferences,
            firstBusiness = { business("someone-else") },
            clearAllTables = { wipes++ },
        )

        assertTrue(guard.adopt("new-user"))
        assertEquals(1, wipes)
    }

    @Test
    fun `without a last user and without a business it does not wipe`() = runTest {
        val preferences = InMemoryKeyValueStore()
        var wipes = 0
        val guard = LocalSessionGuard(
            preferences = preferences,
            firstBusiness = { null },
            clearAllTables = { wipes++ },
        )

        assertFalse(guard.adopt("new-user"))
        assertEquals(0, wipes)
    }

    @Test
    fun `forget removes the last user key`() = runTest {
        val preferences = InMemoryKeyValueStore()
        val guard = LocalSessionGuard(
            preferences = preferences,
            firstBusiness = { null },
            clearAllTables = {},
        )
        guard.adopt("new-user")
        assertEquals("new-user", preferences.getString("lastUserId"))

        guard.forget()

        assertNull(preferences.getString("lastUserId"))
    }
}
