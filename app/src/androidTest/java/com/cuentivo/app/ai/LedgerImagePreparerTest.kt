package com.cuentivo.app.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.ai.ledger.AiLedgerImagePolicy
import com.cuentivo.app.ai.ledger.LedgerImagePreparer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Photographing the page is the main way this feature is used, so a real camera-sized photo has to
 * survive the trip. These run against the device because scaling and EXIF are platform behaviour.
 */
@RunWith(AndroidJUnit4::class)
class LedgerImagePreparerTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "prep-test")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun writeJpeg(name: String, width: Int, height: Int): File {
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    private fun sizeOf(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun a_camera_sized_page_is_scaled_down_instead_of_refused() {
        // 48 megapixels, the shape a modern phone actually produces.
        val file = writeJpeg("big.jpg", 8000, 6000)

        val mime = LedgerImagePreparer.prepareInPlace(file)

        assertEquals("image/jpeg", mime)
        val (width, height) = sizeOf(file)
        assertTrue("Longest edge must come down: " + width + "x" + height, maxOf(width, height) <= LedgerImagePreparer.MAX_EDGE_PIXELS)
        assertTrue("And it must fit what the callable takes", AiLedgerImagePolicy.isWithinCallableCap(file.length()))
    }

    @Test
    fun a_page_that_already_fits_is_not_upscaled() {
        val file = writeJpeg("small.jpg", 1200, 900)

        LedgerImagePreparer.prepareInPlace(file)

        assertEquals(1200 to 900, sizeOf(file))
    }

    @Test
    fun a_sideways_photo_is_rotated_because_the_model_reads_pixels_not_metadata() {
        val file = writeJpeg("rotated.jpg", 2000, 1000)
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        LedgerImagePreparer.prepareInPlace(file)

        val (width, height) = sizeOf(file)
        assertTrue("A page stored sideways must come back upright: " + width + "x" + height, height > width)
    }

    @Test
    fun a_file_that_is_not_an_image_is_refused_rather_than_uploaded() {
        val file = File(dir, "notanimage.jpg")
        file.writeText("esto no es una foto")

        assertNull(LedgerImagePreparer.prepareInPlace(file))
    }

    @Test
    fun the_sample_size_decodes_cheaply_without_throwing_away_detail() {
        // It stops one step BEFORE going under the target, so the precise scaling still has pixels
        // to work with. Halving all the way down is what erased the small raised centavos.
        assertEquals(1, LedgerImagePreparer.sampleSizeFor(800, 600))
        assertEquals(2, LedgerImagePreparer.sampleSizeFor(8000, 6000))
    }

    @Test
    fun a_page_just_over_the_limit_is_not_halved_to_a_quarter() {
        val file = writeJpeg("justover.jpg", 4801, 3600)

        LedgerImagePreparer.prepareInPlace(file)

        val (width, height) = sizeOf(file)
        assertEquals("A page just over the limit must land ON the limit", LedgerImagePreparer.MAX_EDGE_PIXELS, maxOf(width, height))
    }
}
