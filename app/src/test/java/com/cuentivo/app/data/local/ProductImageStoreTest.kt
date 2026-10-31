package com.cuentivo.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure halves of safe product-image handling, tested as plain JVM math so a cheap phone
 * never decodes a several-megapixel photo per row:
 *
 * - [BoundedImageCache]: a byte-budgeted LRU so scrolling a product list reuses decoded bitmaps
 *   instead of re-decoding them, without the cache ever growing without bound.
 * - [ProductImageStore.sampleSizeFor]: the power-of-two sample that keeps both decoded edges under
 *   the display maximum (the same math the save path already uses, now shared with the load path).
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
}

class ProductImageSamplingTest {

    @Test
    fun small_images_keep_sample_size_one() {
        assertEquals(1, ProductImageStore.sampleSizeFor(width = 100, height = 80, maxEdgePx = 512))
    }

    @Test
    fun large_images_downsample_both_edges_under_the_max() {
        val sample = ProductImageStore.sampleSizeFor(width = 4000, height = 3000, maxEdgePx = 512)

        assertEquals(8, sample)
        assertTrue(4000 / sample <= 512)
        assertTrue(3000 / sample <= 512)
    }

    @Test
    fun sample_size_is_always_a_positive_power_of_two() {
        listOf(1000 to 500, 2049 to 1024, 900 to 800, 513 to 1, 1 to 1).forEach { (w, h) ->
            val sample = ProductImageStore.sampleSizeFor(width = w, height = h, maxEdgePx = 512)
            assertTrue("sample $sample for $w x $h", sample >= 1 && (sample and (sample - 1)) == 0)
        }
    }

    @Test
    fun unreadable_dimensions_fall_back_to_a_full_decode() {
        assertEquals(1, ProductImageStore.sampleSizeFor(width = 0, height = 0, maxEdgePx = 512))
        assertEquals(1, ProductImageStore.sampleSizeFor(width = -1, height = 10, maxEdgePx = 512))
    }
}
