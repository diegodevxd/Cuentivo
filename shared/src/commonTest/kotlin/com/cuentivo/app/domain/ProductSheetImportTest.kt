package com.cuentivo.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ProductSheetImportTest {

    @Test
    fun `reads a csv with a header and prices written as people write them`() {
        val csv = "nombre,precio,sku,categoria\n" +
            "Jabon de coco,45.50,JAB-01,Aseo\n" +
            "Shampoo 500 ml,\"\$1,234.50\",SHA-500,Aseo\n" +
            "Sarten 24 cm,349,,Cocina"

        val result = ProductSheetParser.parseCsv(csv, existingNames = emptySet())

        assertEquals(emptyList<RejectedRow>(), result.rejected)
        assertEquals(listOf(4_550L, 123_450L, 34_900L), result.accepted.map { it.priceCents })
        assertEquals("JAB-01", result.accepted.first().sku)
        assertEquals("Aseo", result.accepted.first().category)
    }

    @Test
    fun `a csv saved by Excel with a byte order mark still finds its headers`() {
        // Excel writes U+FEFF at the very start of a UTF-8 CSV. Left in place it glues itself to the
        // first header cell, so "nombre" stops matching, the file falls back to positional columns,
        // and every SKU and category silently lands in the wrong field.
        val bom = Char(0xFEFF).toString()
        val csv = bom + "nombre,precio,sku\nJabon de coco,45.50,JAB-01"

        val result = ProductSheetParser.parseCsv(csv, existingNames = emptySet())

        assertEquals(emptyList<RejectedRow>(), result.rejected)
        assertEquals(1, result.accepted.size)
        val product = result.accepted.first()
        // Name intact — no invisible character clinging to it.
        assertEquals("Jabon de coco", product.name)
        assertEquals(4_550L, product.priceCents)
        // Proof the header row was understood: sku only lands here by name, never by position.
        assertEquals("JAB-01", product.sku)
    }

    @Test
    fun `falls back to name and price columns when the file has no header`() {
        val result = ProductSheetParser.parseCsv("Cubeta,89.00\nEscoba,55", existingNames = emptySet())

        assertEquals(listOf("Cubeta", "Escoba"), result.accepted.map { it.name })
        assertEquals(listOf(8_900L, 5_500L), result.accepted.map { it.priceCents })
    }

    @Test
    fun `a bad row is reported but the good ones still import`() {
        val csv = "nombre,precio\nBueno,10.00\nSinPrecio,\n,25.00\nTresDecimales,10.005\nOtroBueno,20"

        val result = ProductSheetParser.parseCsv(csv, existingNames = emptySet())

        // Partial import on purpose: one broken line must not cost someone their whole catalog.
        assertEquals(listOf("Bueno", "OtroBueno"), result.accepted.map { it.name })
        assertEquals(listOf(3, 4, 5), result.rejected.map { it.lineNumber })
        assertTrue(result.rejected[2].reason.contains("10.005"))
    }

    @Test
    fun `skips names already in the catalog and repeats inside the file`() {
        val existing = setOf(CustomerNameNormalizer.key("Jabón de Coco"))
        val csv = "nombre,precio\nJabon de coco,45\nEscoba,30\nESCOBA,31"

        val result = ProductSheetParser.parseCsv(csv, existingNames = existing)

        assertEquals(listOf("Escoba"), result.accepted.map { it.name })
        assertEquals(2, result.rejected.size)
        assertTrue(result.rejected.all { it.reason.contains("ya existe") })
    }

    @Test
    fun `blank trailing rows are not reported as problems`() {
        val result = ProductSheetParser.parseCsv("nombre,precio\nCubeta,89\n,\n,\n", existingNames = emptySet())

        assertEquals(1, result.accepted.size)
        assertEquals(emptyList<RejectedRow>(), result.rejected)
    }

    @Test
    fun `the shared template parses cleanly through its own parser`() {
        val result = ProductSheetParser.parseCsv(ProductSheetParser.TEMPLATE_CSV, existingNames = emptySet())

        assertEquals(3, result.accepted.size)
        assertEquals(emptyList<RejectedRow>(), result.rejected)
    }

    @Test
    fun `reads an xlsx with shared strings, inline strings and a gap in the row`() {
        val rows = XlsxReader.read(XlsxFixtures.NORMAL)

        assertEquals(listOf("nombre", "precio"), rows[0])
        // The entity is decoded, not left as &amp;.
        assertEquals(listOf("Jabon & jabon", "45.5"), rows[1])
        // B3 is missing from the XML, so the value in C3 has to land in the third column.
        assertEquals(listOf("Escoba", "", "7"), rows[2])
    }

    @Test
    fun `an xlsx imports end to end`() {
        val result = ProductSheetParser.parse(XlsxReader.read(XlsxFixtures.NORMAL), existingNames = emptySet())

        assertEquals(listOf("Jabon & jabon", "Cubeta 20 L"), result.accepted.map { it.name })
        assertEquals(listOf(4_550L, 8_990L), result.accepted.map { it.priceCents })
        // The third row of the fixture has its price in the third column and the header only names
        // two, so it is reported instead of importing something with a made-up price.
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.first().reason.contains("sin precio"))
    }

    @Test
    fun `reads an xlsx whose tags carry a namespace prefix`() {
        // Shaped after a file a shop owner actually brought in: every element prefixed with `x:`,
        // an empty sharedStrings table, and text stored as t="str" with the value inline. The
        // reader used to find no rows at all in this and report an empty catalog with no errors.
        val rows = XlsxReader.read(XlsxFixtures.PREFIXED)

        assertEquals(listOf("Nombre", "Precio"), rows[0])
        assertEquals(listOf("Organizador multiusos mediano", "129.9"), rows[1])
        assertEquals(listOf("Cepillo limpiador para cocina", "89"), rows[2])
    }

    @Test
    fun `a prefixed xlsx imports end to end`() {
        val result = ProductSheetParser.parse(XlsxReader.read(XlsxFixtures.PREFIXED), existingNames = emptySet())

        assertEquals(
            listOf("Organizador multiusos mediano", "Cepillo limpiador para cocina"),
            result.accepted.map { it.name },
        )
        assertEquals(listOf(12_990L, 8_900L), result.accepted.map { it.priceCents })
        assertEquals(emptyList<RejectedRow>(), result.rejected)
    }

    @Test
    fun `a streaming xlsx gives the same rows as a normal one`() {
        // Written to a non-seekable stream, so the local headers say 0 bytes and the real sizes live
        // in a trailing data descriptor. Reading local headers would return nothing here.
        assertEquals(XlsxReader.read(XlsxFixtures.NORMAL), XlsxReader.read(XlsxFixtures.STREAMING))
    }

    @Test
    fun `a stored xlsx gives the same rows as a normal one`() {
        assertEquals(XlsxReader.read(XlsxFixtures.NORMAL), XlsxReader.read(XlsxFixtures.STORED))
    }

    @Test
    fun `bytes that are not a zip give no rows`() {
        assertEquals(emptyList<List<String>>(), XlsxReader.read("PK,precio\nA,1".encodeToByteArray()))
    }

    @Test
    fun `a truncated fixture fails instead of reporting an empty catalog`() {
        assertFails { XlsxReader.read(XlsxFixtures.NORMAL.copyOf(XlsxFixtures.NORMAL.size / 2)) }
    }

    @Test
    fun `a flipped byte inside the compressed data fails instead of importing garbage`() {
        val broken = XlsxFixtures.NORMAL.copyOf()
        val payload = firstPayloadOffset(broken) + 4
        broken[payload] = (broken[payload].toInt() xor 0xFF).toByte()

        assertFails { XlsxReader.read(broken) }
    }

    @Test
    fun `parseFile picks xlsx by signature`() {
        val result = ProductSheetParser.parseFile(XlsxFixtures.NORMAL, existingNames = emptySet())

        assertEquals(listOf("Jabon & jabon", "Cubeta 20 L"), result.accepted.map { it.name })
    }

    @Test
    fun `parseFile picks csv when there is no zip signature`() {
        val result = ProductSheetParser.parseFile("nombre,precio\nCubeta,89".encodeToByteArray(), emptySet())

        assertEquals(listOf("Cubeta"), result.accepted.map { it.name })
        assertEquals(listOf(8_900L), result.accepted.map { it.priceCents })
    }

    @Test
    fun `parseFile on an empty file reports nothing and does not throw`() {
        val result = ProductSheetParser.parseFile(ByteArray(0), emptySet())

        assertEquals(emptyList<ParsedProduct>(), result.accepted)
        assertEquals(emptyList<RejectedRow>(), result.rejected)
    }

    @Test
    fun `a csv that starts with PK is read as xlsx and simply yields no products`() {
        // The signature wins over the content: this is the price of deciding by the first two bytes,
        // and it must not throw — the person sees "no products", not a crash.
        val result = ProductSheetParser.parseFile("PK,precio\nA,1".encodeToByteArray(), emptySet())

        assertEquals(emptyList<ParsedProduct>(), result.accepted)
    }

    /**
     * Offset of the first entry's payload, read from its local header.
     *
     * Only used to corrupt the fixture on purpose; the reading itself is what the other tests check.
     */
    private fun firstPayloadOffset(bytes: ByteArray): Int {
        val nameLength = (bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0xFF) shl 8)
        val extraLength = (bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0xFF) shl 8)
        return 30 + nameLength + extraLength
    }
}
