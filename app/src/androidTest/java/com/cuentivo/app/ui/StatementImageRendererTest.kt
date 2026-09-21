package com.cuentivo.app.ui

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.domain.StatementImage
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.share.StatementImageRenderer
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El dibujo en sí, que necesita `Canvas` y `Paint` de verdad.
 *
 * Qué se dice lo decide y lo prueba `StatementImageTest` en JVM; aquí sólo se comprueba que la imagen
 * salga medible, con color, sin reventar con los extremos, y que dos clientes distintos **no** den la
 * misma imagen —que es lo que caza que se devuelva siempre el mismo bitmap—.
 *
 * Y de aquí salen las tres muestras que Diego mira para aprobar el diseño, con datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class StatementImageRendererTest {

    private val samplesDir: File
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "statement-samples")
            .apply { mkdirs() }

    private fun charge(index: Int, cents: Long, detail: String? = null) =
        StatementEntry("2026-08-%02d".format(index % 28 + 1), "CHARGE", "DEBIT", cents, null, detail = detail)

    private fun payment(index: Int, cents: Long) =
        StatementEntry("2026-08-%02d".format(index % 28 + 1), "PAYMENT", "CREDIT", cents, null)

    private fun content(
        businessName: String? = "Abarrotes Don Luis",
        customerName: String = "Sra. Mendoza",
        balanceCents: Long = 59_900,
        entries: List<StatementEntry> = listOf(charge(3, 79_900, detail = "2 Jabón Zote"), payment(12, 20_000)),
        tier: SubscriptionTier = SubscriptionTier.FREE,
        statementDateIso: String = "2026-09-20",
    ) = StatementImage.build(businessName, customerName, balanceCents, entries, tier, statementDateIso)

    private fun render(content: com.cuentivo.app.domain.StatementImageContent) = StatementImageRenderer.render(content)

    /** Cuántos colores distintos hay en una rejilla de muestras. Un fondo liso da 1. */
    private fun distinctColors(bitmap: Bitmap): Int {
        val seen = mutableSetOf<Int>()
        val stepX = (bitmap.width / 40).coerceAtLeast(1)
        val stepY = (bitmap.height / 60).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                seen += bitmap.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        bitmap.recycle()
        return seen.size
    }

    private fun hashOf(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun el_ancho_es_el_esperado_y_la_imagen_no_sale_en_blanco() {
        val bitmap = render(content())

        assertEquals(StatementImageRenderer.WIDTH_PX, bitmap.width)
        assertTrue("el alto tiene que salir del contenido", bitmap.height > 0)
        assertTrue("un bitmap de un solo color es un fondo, no un estado de cuenta", distinctColors(bitmap) > 3)
    }

    @Test
    fun no_revienta_con_cero_movimientos_ni_con_cuarenta_ni_con_un_nombre_larguisimo() {
        val empty = render(content(entries = emptyList(), balanceCents = 0))
        assertTrue(empty.width == StatementImageRenderer.WIDTH_PX)

        val many = render(content(entries = (1..40).map { charge(it, 10_000L * it) }))
        assertTrue(many.width == StatementImageRenderer.WIDTH_PX)

        // Un nombre que no cabe en una línea: se parte, no se sale del ancho ni revienta.
        val longName = render(
            content(
                businessName = "Papelería La Estrella de la esquina del mercado de Todos los Santos y sus Alrededores",
                customerName = "Sra. Mendoza de los Ángeles y Fernández del Valle",
            ),
        )
        assertEquals(StatementImageRenderer.WIDTH_PX, longName.width)
        assertTrue("el nombre largo tiene que ocupar mas alto que una sola linea", longName.height > render(content()).height)
    }

    @Test
    fun dos_clientes_distintos_dan_imagenes_distintas() {
        // Si el renderizador devolviera siempre el mismo bitmap —o el contenido de otro—, esto cae.
        val mendoza = render(content(customerName = "Sra. Mendoza", entries = listOf(charge(3, 79_900))))
        val estrella = render(content(customerName = "Beto", entries = listOf(charge(4, 12_500)), balanceCents = 12_500))

        assertNotEquals(hashOf(mendoza), hashOf(estrella))
    }

    @Test
    fun el_sello_cambia_la_imagen_de_gratis_frente_a_pro() {
        val free = render(content(tier = SubscriptionTier.FREE))
        val pro = render(content(tier = SubscriptionTier.PRO))

        assertNotEquals("el sello tiene que verse", hashOf(free), hashOf(pro))
        assertTrue("Pro ademas sale mas baja, sin la linea del sello", pro.height < free.height)
        pro.recycle()
    }

    /**
     * Las tres muestras para que Diego apruebe el diseño. Datos inventados de principio a fin: nunca
     * una libreta real.
     */
    @Test
    fun escribe_las_tres_muestras_para_la_revision_de_diseno() {
        val few = render(
            content(
                businessName = "Abarrotes Don Luis",
                customerName = "Sra. Mendoza",
                balanceCents = 59_900,
                entries = listOf(
                    StatementEntry("2026-08-03", "CHARGE", "DEBIT", 79_900, null, detail = "2 Jabón Zote"),
                    StatementEntry("2026-08-12", "PAYMENT", "CREDIT", 20_000, null),
                    StatementEntry("2026-08-18", "CHARGE", "DEBIT", 12_000, null, detail = "1 kg de azúcar"),
                ),
            ),
        )

        val many = render(
            content(
                businessName = "Papelería La Estrella",
                customerName = "Beto",
                balanceCents = 4_310_000,
                entries = (1..40).map { index ->
                    if (index % 4 == 0) payment(index, 5_000) else charge(index, 10_000L * index, detail = "Compra $index")
                },
            ),
        )

        val proNoStamp = render(
            content(
                businessName = "Papelería La Estrella",
                customerName = "Beto",
                balanceCents = 34_900,
                entries = listOf(
                    StatementEntry("2026-08-03", "CHARGE", "DEBIT", 79_900, null, detail = "2 Jabón Zote"),
                    StatementEntry("2026-08-12", "PAYMENT", "CREDIT", 25_000, null),
                    StatementEntry("2026-08-18", "PAYMENT", "CREDIT", 20_000, null),
                ),
                tier = SubscriptionTier.PRO,
            ),
        )

        write("1-gratis-pocos", few)
        write("2-gratis-muchos", many)
        write("3-pro-sin-marca", proNoStamp)
    }

    private fun write(name: String, bitmap: Bitmap) {
        // Igual que las capturas de la ficha: almacenamiento interno, porque desde Android 11 el shell
        // no puede leer los datos de otra app y un `adb pull` de la caché sería invisible.
        File(samplesDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
