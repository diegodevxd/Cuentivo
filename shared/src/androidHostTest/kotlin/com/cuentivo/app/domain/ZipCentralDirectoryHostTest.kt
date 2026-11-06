package com.cuentivo.app.domain

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El lector del directorio central contra `ZipInputStream`, que es lo que Android usa en producción.
 *
 * Esto es lo que hace barato el cambio: si las dos lecturas coinciden en cada fixture y en zips
 * recién hechos, iOS se comporta como Android sin gastar una vuelta de CI en averiguarlo. Compara
 * contenido, no solo claves: un mapa con las mismas entradas y los bytes corridos pasaría el filtro
 * equivocado.
 */
class ZipCentralDirectoryHostTest {

    @Test
    fun `coincide con ZipInputStream en los cuatro fixtures`() {
        val fixtures = mapOf(
            "normal" to XlsxFixtures.NORMAL,
            "streaming" to XlsxFixtures.STREAMING,
            "stored" to XlsxFixtures.STORED,
            "prefixed" to XlsxFixtures.PREFIXED,
        )

        for ((name, bytes) in fixtures) {
            assertSameEntries(name, bytes)
        }
    }

    @Test
    fun `coincide con zips recien hechos por ZipOutputStream`() {
        assertSameEntries("uno", zipOf(mapOf("hola.txt" to "hola")))
        assertSameEntries(
            "varios",
            zipOf(
                linkedMapOf(
                    "xl/sharedStrings.xml" to "<sst/>",
                    "xl/worksheets/sheet1.xml" to "<worksheet>" + "x".repeat(4096) + "</worksheet>",
                    "carpeta/" to "",
                    "carpeta/otro.txt" to "otro",
                ),
            ),
        )
    }

    @Test
    fun `el lector del directorio central no devuelve las entradas que no se piden`() {
        val bytes = XlsxFixtures.NORMAL

        val filtered = ZipCentralDirectory.read(
            bytes,
            { name -> name == "xl/sharedStrings.xml" },
            ::inflateRaw,
        )

        assertEquals(setOf("xl/sharedStrings.xml"), filtered.keys)
        assertTrue(filtered.getValue("xl/sharedStrings.xml").decodeToString().contains("<sst>"))
    }

    private fun assertSameEntries(case: String, bytes: ByteArray) {
        val expected = readZipEntries(bytes) { true }
        val actual = ZipCentralDirectory.read(bytes, { true }, ::inflateRaw)

        assertEquals(expected.keys, actual.keys, "claves distintas en $case")
        for ((entry, content) in expected) {
            assertEquals(content.toList(), actual.getValue(entry).toList(), "contenido distinto en $case/$entry")
        }
    }

    /** `Inflater(true)` es DEFLATE crudo, el mismo formato que el ZIP y que Apple. */
    private fun inflateRaw(compressed: ByteArray, uncompressedSize: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val out = ByteArray(uncompressedSize)
            val written = inflater.inflate(out)
            assertEquals(uncompressedSize, written, "el bloque no mide lo declarado")
            return out
        } finally {
            inflater.end()
        }
    }

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
