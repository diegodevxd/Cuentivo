package com.cuentivo.app.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.ai.ledger.AiLedgerImagePolicy
import com.cuentivo.app.ai.ledger.LedgerImagePreparer
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    /** Small enough to keep the test quick, big enough to survive the scaling untouched. */
    private val SIDE = 400

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

    /**
     * A square image with exactly one corner marked, so every orientation lands somewhere different.
     *
     * The old test used a blank image and compared width against height, which cannot tell 90 from
     * 270 — they have the same dimensions — nor spot a mirrored page at all. A notebook read
     * mirrored is a notebook read wrong, silently.
     */
    private fun writeCornerMarkedJpeg(name: String): File {
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        for (x in 0 until SIDE / 4) {
            for (y in 0 until SIDE / 4) {
                bitmap.setPixel(x, y, android.graphics.Color.BLACK)
            }
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        return file
    }

    /** Which quadrant the dark corner ended up in: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right. */
    private fun darkQuadrant(file: File): Int {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        val q = bitmap.width / 8
        val samples = listOf(
            android.graphics.Color.red(bitmap.getPixel(q, q)),
            android.graphics.Color.red(bitmap.getPixel(bitmap.width - q, q)),
            android.graphics.Color.red(bitmap.getPixel(q, bitmap.height - q)),
            android.graphics.Color.red(bitmap.getPixel(bitmap.width - q, bitmap.height - q)),
        )
        bitmap.recycle()
        return samples.indexOf(samples.min())
    }

    @Test
    fun every_exif_orientation_moves_the_page_somewhere_different() {
        // Eight orientations, compared by pixels and not by dimensions: on a square page the four
        // rotations all measure the same, and a mirrored one measures the same as the original.
        val orientations = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
        )

        val landedIn = orientations.map { orientation ->
            val file = writeCornerMarkedJpeg("orient-" + orientation + ".jpg")
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            LedgerImagePreparer.prepareInPlace(file)
            orientation to darkQuadrant(file)
        }

        // Untouched, the mark stays where it was drawn.
        assertEquals(0, landedIn.first { it.first == ExifInterface.ORIENTATION_NORMAL }.second)
        // A quarter turn clockwise carries the top-left corner to the top-right.
        assertEquals(1, landedIn.first { it.first == ExifInterface.ORIENTATION_ROTATE_90 }.second)
        assertEquals(3, landedIn.first { it.first == ExifInterface.ORIENTATION_ROTATE_180 }.second)
        assertEquals(2, landedIn.first { it.first == ExifInterface.ORIENTATION_ROTATE_270 }.second)
        // Mirrored pages: these used to be ignored entirely, so the page travelled backwards.
        assertEquals(1, landedIn.first { it.first == ExifInterface.ORIENTATION_FLIP_HORIZONTAL }.second)
        assertEquals(2, landedIn.first { it.first == ExifInterface.ORIENTATION_FLIP_VERTICAL }.second)
        // And 90 must not be mistaken for 270, which is exactly what comparing sizes could not see.
        assertNotEquals(
            landedIn.first { it.first == ExifInterface.ORIENTATION_ROTATE_90 }.second,
            landedIn.first { it.first == ExifInterface.ORIENTATION_ROTATE_270 }.second,
        )
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

    /**
     * Una foto sin EXIF ya viene con los píxeles como están: no hay nada que rotar.
     *
     * Lo que NO puede pasar es que la ausencia de metadatos se convierta en un fallo y la página se
     * pierda. Y el cuadrante importa: comparar sólo dimensiones no distingue "no se tocó" de "se rotó
     * 180 grados".
     */
    @Test
    fun a_page_without_any_exif_comes_out_untouched_and_does_not_fail() {
        val file = writeCornerMarkedJpeg("sin-exif.jpg")
        val sizeBefore = sizeOf(file)
        val quadrantBefore = darkQuadrant(file)

        val mime = LedgerImagePreparer.prepareInPlace(file)

        assertEquals("Lo que falta es el metadato, no la foto: no puede fallar", "image/jpeg", mime)
        assertEquals(sizeBefore, sizeOf(file))
        assertEquals(quadrantBefore, darkQuadrant(file))
    }

    /** Un valor de orientación que la matriz no conoce (0, UNDEFINED) tiene que dejar la hoja igual. */
    @Test
    fun an_orientation_the_matrix_does_not_know_leaves_the_page_alone() {
        val file = writeCornerMarkedJpeg("orientacion-desconocida.jpg")
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED.toString())
            saveAttributes()
        }
        val sizeBefore = sizeOf(file)
        val quadrantBefore = darkQuadrant(file)

        val mime = LedgerImagePreparer.prepareInPlace(file)

        assertEquals("image/jpeg", mime)
        assertEquals(sizeBefore, sizeOf(file))
        assertEquals(quadrantBefore, darkQuadrant(file))
    }

    /**
     * Un fallo a mitad de camino no puede dejar el original dañado: `prepareInPlace` escribe en un
     * archivo aparte y sólo sustituye el original si todo salió bien (`LedgerImagePreparer.kt:129-149`).
     * Esto lo deja por escrito, que es lo que faltaba.
     */
    @Test
    fun a_failed_prepare_does_not_touch_the_original() {
        val file = File(dir, "no-es-imagen.jpg")
        file.writeText("esto no es una foto")
        val before = file.readBytes()

        assertNull(LedgerImagePreparer.prepareInPlace(file))

        assertArrayEquals("el original tiene que quedar byte por byte como estaba", before, file.readBytes())
    }
}
