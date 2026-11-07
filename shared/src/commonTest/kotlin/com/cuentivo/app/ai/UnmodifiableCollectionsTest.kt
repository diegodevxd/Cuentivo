package com.cuentivo.app.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnmodifiableCollectionsTest {

    @Test
    fun map_reads_through() {
        val map = unmodifiableMap(mapOf("a" to 1L, "b" to 2L))

        assertEquals(2, map.size)
        assertEquals(1L, map["a"])
        assertTrue(map.containsKey("b"))
    }

    @Test
    fun map_refuses_put_by_cast() {
        val map = unmodifiableMap(mapOf("a" to 1L, "b" to 2L))

        assertFailsWith<UnsupportedOperationException> {
            (map as MutableMap<String, Long>).put("b", 2L)
        }
    }

    @Test
    fun map_refuses_remove_by_cast() {
        val map = unmodifiableMap(mapOf("a" to 1L, "b" to 2L))

        assertFailsWith<UnsupportedOperationException> {
            (map as MutableMap<String, Long>).remove("a")
        }
    }

    @Test
    fun map_entry_refuses_set_value() {
        val map = unmodifiableMap(mapOf("a" to 1L))
        val entry = (map as MutableMap<String, Long>).entries.first()

        assertFailsWith<UnsupportedOperationException> {
            entry.setValue(2L)
        }
    }

    @Test
    fun map_equals_a_plain_map() {
        assertEquals(mapOf("a" to 1L, "b" to 2L), unmodifiableMap(mapOf("a" to 1L, "b" to 2L)))
    }
}
