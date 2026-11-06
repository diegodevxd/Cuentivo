package com.cuentivo.app.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El contrato de [AppFiles], ejercido contra el disco real de cada plataforma.
 *
 * La lógica de "qué nombre vale" es común, pero "no toca el disco" sólo se puede comprobar donde hay
 * disco: de ahí que esto sea abstracto y que cada plataforma ponga su directorio temporal.
 */
abstract class AppFilesContractTest {

    abstract fun newFiles(): AppFiles

    /**
     * Nombres que hay en el directorio **padre** de las dos raíces (la "carpeta del caso").
     *
     * Se toma una foto antes de los intentos inválidos y se compara después: si alguno se escapó con
     * `..`, aparece aquí y la prueba falla.
     */
    abstract fun outsideEntries(files: AppFiles): List<String>

    private val directory = "fotos"
    private val otherDirectory = "otro"
    private val fileName = "a.jpg"

    @Test
    fun `escribe y lee lo mismo`() {
        val files = newFiles()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        assertTrue(files.writeBytes(FileArea.PERSISTENT, directory, fileName, bytes))

        assertContentEquals(bytes, files.readBytes(FileArea.PERSISTENT, directory, fileName))
        assertEquals(listOf(fileName), files.list(FileArea.PERSISTENT, directory))
        assertNotNull(files.absolutePath(FileArea.PERSISTENT, directory, fileName))
    }

    @Test
    fun `cero bytes`() {
        val files = newFiles()

        assertTrue(files.writeBytes(FileArea.CACHE, directory, fileName, ByteArray(0)))

        val read = files.readBytes(FileArea.CACHE, directory, fileName)
        assertNotNull(read)
        assertEquals(0, read.size)
        assertNotNull(files.absolutePath(FileArea.CACHE, directory, fileName))
    }

    @Test
    fun `sobrescribe`() {
        val files = newFiles()

        assertTrue(files.writeBytes(FileArea.PERSISTENT, directory, fileName, byteArrayOf(1)))
        assertTrue(files.writeBytes(FileArea.PERSISTENT, directory, fileName, byteArrayOf(9, 8)))

        assertContentEquals(byteArrayOf(9, 8), files.readBytes(FileArea.PERSISTENT, directory, fileName))
        assertEquals(listOf(fileName), files.list(FileArea.PERSISTENT, directory))
    }

    @Test
    fun `borra`() {
        val files = newFiles()
        assertTrue(files.writeBytes(FileArea.PERSISTENT, directory, fileName, byteArrayOf(1)))

        assertTrue(files.delete(FileArea.PERSISTENT, directory, fileName))

        assertNull(files.readBytes(FileArea.PERSISTENT, directory, fileName))
        assertTrue(files.list(FileArea.PERSISTENT, directory).isEmpty())
        assertNull(files.absolutePath(FileArea.PERSISTENT, directory, fileName))
        assertFalse(files.delete(FileArea.PERSISTENT, directory, fileName))
    }

    @Test
    fun `clear no toca otro directorio`() {
        val files = newFiles()
        assertTrue(files.writeBytes(FileArea.CACHE, directory, "uno.txt", byteArrayOf(1)))
        assertTrue(files.writeBytes(FileArea.CACHE, directory, "dos.txt", byteArrayOf(2)))
        assertTrue(files.writeBytes(FileArea.CACHE, otherDirectory, "tres.txt", byteArrayOf(3)))

        files.clear(FileArea.CACHE, directory)

        assertTrue(files.list(FileArea.CACHE, directory).isEmpty())
        assertEquals(listOf("tres.txt"), files.list(FileArea.CACHE, otherDirectory))
        assertContentEquals(byteArrayOf(3), files.readBytes(FileArea.CACHE, otherDirectory, "tres.txt"))
    }

    @Test
    fun `las areas no se mezclan`() {
        val files = newFiles()
        assertTrue(files.writeBytes(FileArea.CACHE, directory, fileName, byteArrayOf(1)))
        assertTrue(files.writeBytes(FileArea.PERSISTENT, directory, fileName, byteArrayOf(2)))

        assertContentEquals(byteArrayOf(1), files.readBytes(FileArea.CACHE, directory, fileName))
        assertContentEquals(byteArrayOf(2), files.readBytes(FileArea.PERSISTENT, directory, fileName))

        files.clear(FileArea.CACHE, directory)

        assertNull(files.readBytes(FileArea.CACHE, directory, fileName))
        assertContentEquals(byteArrayOf(2), files.readBytes(FileArea.PERSISTENT, directory, fileName))
    }

    @Test
    fun `lista solo los archivos escribidos`() {
        val files = newFiles()
        assertTrue(files.writeBytes(FileArea.PERSISTENT, directory, "b.txt", byteArrayOf(1)))
        assertTrue(files.writeBytes(FileArea.PERSISTENT, directory, "a.txt", byteArrayOf(2)))

        assertEquals(listOf("a.txt", "b.txt"), files.list(FileArea.PERSISTENT, directory))
    }

    @Test
    fun `leer lo que no existe da null`() {
        val files = newFiles()

        assertNull(files.readBytes(FileArea.PERSISTENT, directory, "fantasma.jpg"))
        assertNull(files.absolutePath(FileArea.PERSISTENT, directory, "fantasma.jpg"))
        assertTrue(files.list(FileArea.PERSISTENT, "carpeta-que-nadie-creo").isEmpty())
        // Un segmento válido sobre un directorio inexistente tampoco crea nada.
        files.clear(FileArea.PERSISTENT, "carpeta-que-nadie-creo")
        assertTrue(files.list(FileArea.PERSISTENT, "carpeta-que-nadie-creo").isEmpty())
    }

    @Test
    fun `segmentos invalidos no tocan el disco`() {
        val files = newFiles()
        // Una escritura válida crea las raíces; la foto de "fuera" se toma después.
        assertTrue(files.writeBytes(FileArea.PERSISTENT, directory, "valido.txt", byteArrayOf(1)))
        val outsideBefore = outsideEntries(files)

        for (segment in INVALID_SEGMENTS) {
            assertNull(files.readBytes(FileArea.CACHE, segment, "x.txt"), "leer con directorio '$segment'")
            assertFalse(files.writeBytes(FileArea.CACHE, segment, "x.txt", byteArrayOf(1)), "escribir con directorio '$segment'")
            assertTrue(files.list(FileArea.CACHE, segment).isEmpty(), "listar con directorio '$segment'")
            assertNull(files.absolutePath(FileArea.CACHE, segment, "x.txt"), "ruta con directorio '$segment'")

            assertNull(files.readBytes(FileArea.CACHE, directory, segment), "leer con nombre '$segment'")
            assertFalse(files.writeBytes(FileArea.CACHE, directory, segment, byteArrayOf(1)), "escribir con nombre '$segment'")
            assertFalse(files.delete(FileArea.CACHE, directory, segment), "borrar con nombre '$segment'")
            assertNull(files.absolutePath(FileArea.CACHE, directory, segment), "ruta con nombre '$segment'")

            // `clear` no devuelve nada: lo que se comprueba es que no truene y no cree nada.
            files.clear(FileArea.CACHE, segment)
        }

        assertEquals(outsideBefore, outsideEntries(files))
        assertEquals(listOf("valido.txt"), files.list(FileArea.PERSISTENT, directory))
    }

    private companion object {
        /**
         * Cada uno abre una forma de salirse del directorio o de nombrar algo que no es un archivo:
         * vacío, en blanco, separadores de los dos sistemas, los dos puntos de navegación y NUL.
         */
        val INVALID_SEGMENTS = listOf(
            "",
            "   ",
            "/",
            "\\",
            ".",
            "..",
            "a/b",
            "a\\b",
            "\u0000",
            "a\u0000b",
        )
    }
}
