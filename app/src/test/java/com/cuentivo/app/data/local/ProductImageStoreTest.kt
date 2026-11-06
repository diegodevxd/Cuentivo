package com.cuentivo.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of safe product-image handling, tested as plain JVM math so a cheap phone never
 * decodes a several-megapixel photo per row:
 *
 * - [ProductImageStore.sampleSizeFor]: the power-of-two sample that keeps both decoded edges under
 *   the display maximum (the same math the save path already uses, now shared with the load path).
 *
 * [BoundedImageCache] vive en `shared/src/commonTest`: su contrato es puro y así corre también en
 * iOS.
 */
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
