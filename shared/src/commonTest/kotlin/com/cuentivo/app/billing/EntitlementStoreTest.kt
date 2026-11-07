package com.cuentivo.app.billing

import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.platform.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntitlementStoreTest {

    @Test
    fun `the keys are the same literals as before`() {
        val preferences = InMemoryKeyValueStore()
        val store = EntitlementStore(preferences)

        store.remember("user-1", SubscriptionTier.PRO, 123L)

        assertEquals("user-1", preferences.getString("entitlementUserId"))
        assertEquals("PRO", preferences.getString("entitlementTier"))
        assertEquals(123L, preferences.getLong("entitlementVerifiedAt", -1L))
        assertEquals("cuentivo.entitlement", EntitlementStore.PREFERENCES)
    }

    @Test
    fun `a blank user id writes nothing`() {
        val preferences = InMemoryKeyValueStore()
        val store = EntitlementStore(preferences)

        store.remember("  ", SubscriptionTier.AI, 9L)

        assertNull(preferences.getString("entitlementUserId"))
        assertEquals(-1L, preferences.getLong("entitlementVerifiedAt", -1L))
    }

    @Test
    fun `forget removes the three keys`() {
        val preferences = InMemoryKeyValueStore()
        val store = EntitlementStore(preferences)
        store.remember("user-1", SubscriptionTier.PRO, 1L)

        store.forget()

        assertNull(preferences.getString("entitlementUserId"))
        assertNull(preferences.getString("entitlementTier"))
        assertEquals(0L, preferences.getLong("entitlementVerifiedAt", 0L))
    }
}
