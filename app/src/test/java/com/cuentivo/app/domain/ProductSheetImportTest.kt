package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        val bytes = xlsx(
            sharedStrings = listOf("nombre", "precio", "Jabon & jabon"),
            sheet = "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>45.5</v></c></row>" +
                "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>Escoba</t></is></c><c r=\"C3\"><v>7</v></c></row>",
        )

        val rows = XlsxReader.read(bytes)

        assertEquals(listOf("nombre", "precio"), rows[0])
        // The entity is decoded, not left as &amp;.
        assertEquals(listOf("Jabon & jabon", "45.5"), rows[1])
        // B3 is missing from the XML, so the value in C3 has to land in the third column.
        assertEquals(listOf("Escoba", "", "7"), rows[2])
    }

    @Test
    fun `an xlsx imports end to end`() {
        val bytes = xlsx(
            sharedStrings = listOf("nombre", "precio", "Cubeta 20 L"),
            sheet = "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>89.9</v></c></row>",
        )

        val result = ProductSheetParser.parse(XlsxReader.read(bytes), existingNames = emptySet())

        assertEquals(listOf("Cubeta 20 L"), result.accepted.map { it.name })
        assertEquals(listOf(8_990L), result.accepted.map { it.priceCents })
    }

    @Test
    fun `reads an xlsx whose tags carry a namespace prefix`() {
        // Shaped after a file a shop owner actually brought in: every element prefixed with `x:`,
        // an empty sharedStrings table, and text stored as t="str" with the value inline. The
        // reader used to find no rows at all in this and report an empty catalog with no errors.
        val bytes = prefixedXlsx(
            "<x:row r=\"1\"><x:c r=\"A1\" t=\"str\"><x:v>Nombre</x:v></x:c>" +
                "<x:c r=\"B1\" t=\"str\"><x:v>Precio</x:v></x:c></x:row>" +
                "<x:row r=\"2\"><x:c r=\"A2\" t=\"str\"><x:v>Organizador multiusos mediano</x:v></x:c>" +
                "<x:c r=\"B2\" t=\"n\"><x:v>129.9</x:v></x:c></x:row>" +
                "<x:row r=\"3\"><x:c r=\"A3\" t=\"str\"><x:v>Cepillo limpiador para cocina</x:v></x:c>" +
                "<x:c r=\"B3\" t=\"n\"><x:v>89</x:v></x:c></x:row>",
        )

        val rows = XlsxReader.read(bytes)

        assertEquals(listOf("Nombre", "Precio"), rows[0])
        assertEquals(listOf("Organizador multiusos mediano", "129.9"), rows[1])
        assertEquals(listOf("Cepillo limpiador para cocina", "89"), rows[2])
    }

    @Test
    fun `a prefixed xlsx imports end to end`() {
        val bytes = prefixedXlsx(
            "<x:row r=\"1\"><x:c r=\"A1\" t=\"str\"><x:v>Nombre</x:v></x:c>" +
                "<x:c r=\"B1\" t=\"str\"><x:v>Precio</x:v></x:c></x:row>" +
                "<x:row r=\"2\"><x:c r=\"A2\" t=\"str\"><x:v>Contenedor hermetico 1 litro</x:v></x:c>" +
                "<x:c r=\"B2\" t=\"n\"><x:v>74.5</x:v></x:c></x:row>",
        )

        val result = ProductSheetParser.parse(XlsxReader.read(bytes), existingNames = emptySet())

        assertEquals(listOf("Contenedor hermetico 1 litro"), result.accepted.map { it.name })
        assertEquals(listOf(7_450L), result.accepted.map { it.priceCents })
        assertEquals(emptyList<RejectedRow>(), result.rejected)
    }

    /** The same workbook written the other legal way: namespace on a prefix instead of the default. */
    private fun prefixedXlsx(sheet: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write("<?xml version=\"1.0\"?><x:sst xmlns:x=\"$SPREADSHEET_NS\" />".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(
                ("<?xml version=\"1.0\"?><x:worksheet xmlns:x=\"$SPREADSHEET_NS\">" +
                    "<x:sheetData>$sheet</x:sheetData></x:worksheet>").toByteArray(),
            )
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    /** The smallest zip the reader needs: shared strings plus one worksheet. */
    private fun xlsx(sharedStrings: List<String>, sheet: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            val items = sharedStrings.joinToString("") { "<si><t>${it.replace("&", "&amp;")}</t></si>" }
            zip.write("<?xml version=\"1.0\"?><sst>$items</sst>".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write("<?xml version=\"1.0\"?><worksheet><sheetData>$sheet</sheetData></worksheet>".toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private companion object {
        const val SPREADSHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    }
}
