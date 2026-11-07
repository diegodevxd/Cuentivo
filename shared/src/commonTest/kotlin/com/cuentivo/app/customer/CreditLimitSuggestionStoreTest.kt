package com.cuentivo.app.customer

import com.cuentivo.app.platform.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreditLimitSuggestionStoreTest {

    @Test
    fun `dismiss stores the amount under the customer key`() {
        val preferences = InMemoryKeyValueStore()
        val store = CreditLimitSuggestionStore(preferences)

        store.dismiss("customer-1", 500L)

        assertEquals(500L, preferences.getLong("dismissed_customer-1", -1L))
        assertTrue(store.isDismissed("customer-1", 500L))
    }

    @Test
    fun `another amount is not the dismissed one`() {
        val preferences = InMemoryKeyValueStore()
        val store = CreditLimitSuggestionStore(preferences)
        store.dismiss("customer-1", 500L)

        assertFalse(store.isDismissed("customer-1", 501L))
    }

    @Test
    fun `the preferences name does not change`() {
        assertEquals("cuentivo.credit_limit_suggestions", CreditLimitSuggestionStore.PREFERENCES)
    }
}
