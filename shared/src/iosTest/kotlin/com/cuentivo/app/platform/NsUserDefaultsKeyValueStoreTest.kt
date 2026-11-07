package com.cuentivo.app.platform

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID

class NsUserDefaultsKeyValueStoreTest {

    private val suite = "cuentivo.test.keyvalue.${NSUUID().UUIDString}"
    private val defaults = NSUserDefaults(suiteName = suite)
    private val store = NsUserDefaultsKeyValueStore("scopeA", defaults)

    @AfterTest
    fun cleanUp() {
        defaults.removePersistentDomainForName(suite)
    }

    @Test
    fun `long max value round trips`() {
        store.edit { putLong("k", Long.MAX_VALUE) }
        assertEquals(Long.MAX_VALUE, store.getLong("k", 0L))
    }

    @Test
    fun `missing key returns the default even minus one`() {
        assertEquals(-1L, store.getLong("missing", -1L))
        assertEquals(7L, store.getLong("missing", 7L))
    }

    @Test
    fun `remove deletes the key`() {
        store.edit { putString("k", "v") }
        store.edit { remove("k") }
        assertNull(store.getString("k"))
    }

    @Test
    fun `two names do not overwrite each other`() {
        val other = NsUserDefaultsKeyValueStore("scopeB", defaults)
        store.edit { putString("k", "a") }
        other.edit { putString("k", "b") }
        assertEquals("a", store.getString("k"))
        assertEquals("b", other.getString("k"))
    }
}
