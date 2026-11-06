package com.cuentivo.app.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The LRU half of safe product-image handling, tested as plain byte math so a cheap phone never
 * decodes a several-megapixel photo per row:
 *
 * - [BoundedImageCache]: a byte-budgeted LRU so scrolling a product list reuses decoded bitmaps
 *   instead of re-decoding them, without the cache ever growing without bound.
 *
 * Las cinco primeras pruebas son las mismas que vivían en `app/src/test`; la sexta cubre que
 * reemplazar una clave también refresca su recencia, que es lo que el `LinkedHashMap` por inserción
 * no hace gratis.
 */
class BoundedImageCacheTest {

    @Test
    fun evicts_the_eldest_entry_when_the_budget_is_exceeded() {
        val cache = BoundedImageCache<ByteArray>(maxBytes = 100, sizeOf = { it.size.toLong() })

        cache.put("a", ByteArray(60))
        cache.put("b", ByteArray(60))

        // 120 bytes total in a 100-byte budget: the eldest ("a") must go.
        assertEquals(listOf("b"), cache.keys())
        assertNull(cache.get("a"))
        assertEquals(60L, cache.sizeBytes())
    }

    @Test
    fun a_get_refreshes_the_lru_recency() {
        val cache = BoundedImageCache<ByteArray>(maxBytes = 100, sizeOf = { it.size.toLong() })

        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache.get("a") // touch "a": it is now the most recently used
        cache.put("c", ByteArray(40)) // 120 > 100 → evict the least recently used = "b"

        assertEquals(listOf("a", "c"), cache.keys())
    }

    @Test
    fun remove_drops_the_entry_and_frees_the_budget() {
        val cache = BoundedImageCache<ByteArray>(maxBytes = 100, sizeOf = { it.size.toLong() })
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))

        cache.remove("a")

        assertEquals(listOf("b"), cache.keys())
        assertEquals(40L, cache.sizeBytes())
    }

    @Test
    fun an_entry_larger_than_the_budget_is_never_retained() {
        val cache = BoundedImageCache<ByteArray>(maxBytes = 50, sizeOf = { it.size.toLong() })

        cache.put("huge", ByteArray(200))

        assertEquals(0, cache.keys().size)
        assertEquals(0L, cache.sizeBytes())
    }

    @Test
    fun replacing_a_key_recounts_the_budget() {
        val cache = BoundedImageCache<ByteArray>(maxBytes = 100, sizeOf = { it.size.toLong() })
        cache.put("a", ByteArray(40))
        cache.put("a", ByteArray(70)) // same key, bigger payload: 40 must leave the budget

        assertEquals(listOf("a"), cache.keys())
        assertEquals(70L, cache.sizeBytes())
    }

    @Test
    fun replacing_a_key_also_makes_it_the_most_recent() {
        val cache = BoundedImageCache<ByteArray>(maxBytes = 100, sizeOf = { it.size.toLong() })

        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache.put("a", ByteArray(40)) // replacing "a" must refresh it, not leave it as the eldest
        cache.put("c", ByteArray(40)) // 120 > 100 → the eldest is now "b"

        assertEquals(listOf("a", "c"), cache.keys())
        assertNull(cache.get("b"))
        assertTrue(cache.get("a") != null)
    }
}
