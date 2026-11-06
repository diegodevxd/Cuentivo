package com.cuentivo.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * El lector del directorio central, con zips armados a mano byte por byte.
 *
 * Armarlos a mano es lo que permite provocar cada caso de la tabla: ZIP64, multidisco, cifrado,
 * método raro, un `extra` local de distinto largo que el central, un comentario en el EOCD. Con un
 * `ZipOutputStream` no se pueden pedir esos archivos.
 *
 * Los CRC son fijos, calculados con `zlib.crc32` de Python. No se recalcula con el código de
 * producción: una prueba que usa el mismo cálculo que verifica no prueba nada.
 */
class ZipCentralDirectoryTest {

    private val inflateNeverCalled: (ByteArray, Int) -> ByteArray = { _, _ ->
        fail("no debía inflar ninguna entrada")
    }

    @Test
    fun `un archivo que no es zip devuelve un mapa vacio`() {
        val entries = ZipCentralDirectory.read("PK,precio\nA,1".encodeToByteArray(), { true }, inflateNeverCalled)

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `un arreglo vacio devuelve un mapa vacio`() {
        assertTrue(ZipCentralDirectory.read(ByteArray(0), { true }, inflateNeverCalled).isEmpty())
    }

    @Test
    fun `un zip truncado sin fin de directorio lanza`() {
        assertFails { ZipCentralDirectory.read(zip(part("a.txt", "hola", crc = CRC_HOLA)).copyOf(40), { true }, inflateNeverCalled) }
    }

    @Test
    fun `lee lo que declara el directorio central`() {
        val entries = ZipCentralDirectory.read(
            zip(part("a.txt", "hola", crc = CRC_HOLA), part("b.txt", "mundo!", crc = CRC_MUNDO)),
            { true },
            inflateNeverCalled,
        )

        assertEquals(setOf("a.txt", "b.txt"), entries.keys)
        assertEquals("hola", entries.getValue("a.txt").decodeToString())
        assertEquals("mundo!", entries.getValue("b.txt").decodeToString())
    }

    @Test
    fun `el extra local distinto del central no corre el inicio de los datos`() {
        // Seven bytes of local extra against three in the directory: taking the directory's length
        // would start the payload four bytes early and read padding as if it were the file.
        val entries = ZipCentralDirectory.read(
            zip(
                part(
                    "a.txt",
                    "hola",
                    crc = CRC_HOLA,
                    localExtra = ByteArray(7) { 0xAA.toByte() },
                    centralExtra = ByteArray(3) { 0xBB.toByte() },
                ),
            ),
            { true },
            inflateNeverCalled,
        )

        assertEquals("hola", entries.getValue("a.txt").decodeToString())
    }

    @Test
    fun `un comentario en el fin de directorio no impide encontrarlo`() {
        val entries = ZipCentralDirectory.read(
            zip(part("a.txt", "hola", crc = CRC_HOLA), comment = "respaldo del 2026-11-06"),
            { true },
            inflateNeverCalled,
        )

        assertEquals("hola", entries.getValue("a.txt").decodeToString())
    }

    @Test
    fun `los nombres que terminan en diagonal se omiten`() {
        val entries = ZipCentralDirectory.read(
            zip(
                part("carpeta/", ByteArray(0), crc = 0),
                part("a.txt", "hola", crc = CRC_HOLA),
            ),
            { true },
            inflateNeverCalled,
        )

        assertEquals(setOf("a.txt"), entries.keys)
    }

    @Test
    fun `un nombre repetido deja el ultimo`() {
        val entries = ZipCentralDirectory.read(
            zip(
                part("a.txt", "uno", crc = CRC_UNO),
                part("a.txt", "dos", crc = CRC_DOS),
            ),
            { true },
            inflateNeverCalled,
        )

        assertEquals("dos", entries.getValue("a.txt").decodeToString())
    }

    @Test
    fun `un tamaño de entrada mayor de 64 MiB lanza antes de inflar`() {
        // The payload would inflate fine; the only reason to fail is the declared size. Asserting
        // only `assertFails` would pass even without the limit (the inflater would be called and the
        // size check would fail afterwards), so the inflater has to record whether it ran.
        var inflated = false

        val outcome = runCatching {
            ZipCentralDirectory.read(
                zip(
                    part(
                        "grande.txt",
                        byteArrayOf(1, 2, 3),
                        method = METHOD_DEFLATE,
                        crc = 0,
                        declaredUncompressed = THREE_GIB,
                    ),
                ),
                { true },
            ) { _, _ ->
                inflated = true
                ByteArray(0)
            }
        }

        assertTrue(outcome.isFailure, "un tamaño declarado de 3 GiB tiene que lanzar")
        assertTrue(!inflated, "el límite tiene que cortar antes de llamar al inflador")
    }

    @Test
    fun `la suma de lo pedido mayor de 128 MiB lanza`() {
        val sixtyMib = 60L * 1024 * 1024
        var inflated = false

        val outcome = runCatching {
            ZipCentralDirectory.read(
                zip(
                    part("uno.txt", byteArrayOf(1), method = METHOD_DEFLATE, crc = 0, declaredUncompressed = sixtyMib),
                    part("dos.txt", byteArrayOf(1), method = METHOD_DEFLATE, crc = 0, declaredUncompressed = sixtyMib),
                    part("tres.txt", byteArrayOf(1), method = METHOD_DEFLATE, crc = 0, declaredUncompressed = sixtyMib),
                ),
                { true },
            ) { _, _ ->
                inflated = true
                ByteArray(0)
            }
        }

        assertTrue(outcome.isFailure, "tres entradas de 60 MiB tienen que lanzar")
        assertTrue(!inflated, "el total se comprueba antes de inflar nada")
    }

    @Test
    fun `lo que wanted rechaza no se infla`() {
        val entries = ZipCentralDirectory.read(
            zip(part("a.txt", byteArrayOf(1, 2, 3), method = METHOD_DEFLATE, crc = 0, declaredUncompressed = 3)),
            { false },
            inflateNeverCalled,
        )

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `un CRC que no coincide lanza`() {
        assertFails {
            ZipCentralDirectory.read(
                zip(part("a.txt", "hola", crc = CRC_MUNDO)),
                { true },
                inflateNeverCalled,
            )
        }
    }

    @Test
    fun `una entrada sin datos comprimidos y con tamaño lanza sin inflar`() {
        assertFails {
            ZipCentralDirectory.read(
                zip(
                    part(
                        "a.txt",
                        ByteArray(0),
                        method = METHOD_DEFLATE,
                        crc = 0,
                        declaredCompressed = 0,
                        declaredUncompressed = 10,
                    ),
                ),
                { true },
                inflateNeverCalled,
            )
        }
    }

    @Test
    fun `una entrada de tamaño cero devuelve vacio y comprueba el CRC`() {
        val entries = ZipCentralDirectory.read(
            zip(part("vacio.txt", ByteArray(0), crc = 0)),
            { true },
            inflateNeverCalled,
        )

        assertEquals(0, entries.getValue("vacio.txt").size)

        assertFails {
            ZipCentralDirectory.read(
                zip(part("vacio.txt", ByteArray(0), crc = CRC_HOLA)),
                { true },
                inflateNeverCalled,
            )
        }
    }

    @Test
    fun `si el inflador devuelve otro tamaño lanza`() {
        assertFails {
            ZipCentralDirectory.read(
                zip(
                    part(
                        "a.txt",
                        byteArrayOf(1, 2, 3),
                        method = METHOD_DEFLATE,
                        crc = 0,
                        declaredUncompressed = 10,
                    ),
                ),
                { true },
                { _, _ -> ByteArray(3) },
            )
        }
    }

    @Test
    fun `ZIP64 lanza`() {
        assertFails {
            ZipCentralDirectory.read(
                zip(part("a.txt", "hola", crc = CRC_HOLA, declaredUncompressed = 0xFFFFFFFFL)),
                { true },
                inflateNeverCalled,
            )
        }
    }

    @Test
    fun `un ZIP multidisco lanza`() {
        assertFails {
            ZipCentralDirectory.read(
                zip(part("a.txt", "hola", crc = CRC_HOLA), diskNumber = 1),
                { true },
                inflateNeverCalled,
            )
        }
    }

    @Test
    fun `un ZIP cifrado lanza`() {
        assertFails {
            ZipCentralDirectory.read(
                zip(part("a.txt", "hola", crc = CRC_HOLA, flags = 0x0001)),
                { true },
                inflateNeverCalled,
            )
        }
    }

    @Test
    fun `un metodo de compresion que no es 0 ni 8 lanza`() {
        assertFails {
            ZipCentralDirectory.read(
                zip(part("a.txt", "hola", method = 12, crc = CRC_HOLA)),
                { true },
                inflateNeverCalled,
            )
        }
    }

    private fun part(
        name: String,
        payload: String,
        method: Int = METHOD_STORED,
        flags: Int = 0,
        crc: Long,
        declaredCompressed: Long = payload.encodeToByteArray().size.toLong(),
        declaredUncompressed: Long = payload.encodeToByteArray().size.toLong(),
        localExtra: ByteArray = ByteArray(0),
        centralExtra: ByteArray = ByteArray(0),
    ) = Part(
        name = name,
        payload = payload.encodeToByteArray(),
        method = method,
        flags = flags,
        crc = crc,
        declaredCompressed = declaredCompressed,
        declaredUncompressed = declaredUncompressed,
        localExtra = localExtra,
        centralExtra = centralExtra,
    )

    private fun part(
        name: String,
        payload: ByteArray,
        method: Int = METHOD_STORED,
        flags: Int = 0,
        crc: Long,
        declaredCompressed: Long = payload.size.toLong(),
        declaredUncompressed: Long = payload.size.toLong(),
        localExtra: ByteArray = ByteArray(0),
        centralExtra: ByteArray = ByteArray(0),
    ) = Part(
        name = name,
        payload = payload,
        method = method,
        flags = flags,
        crc = crc,
        declaredCompressed = declaredCompressed,
        declaredUncompressed = declaredUncompressed,
        localExtra = localExtra,
        centralExtra = centralExtra,
    )

    private class Part(
        val name: String,
        val payload: ByteArray,
        val method: Int,
        val flags: Int,
        val crc: Long,
        val declaredCompressed: Long,
        val declaredUncompressed: Long,
        val localExtra: ByteArray,
        val centralExtra: ByteArray,
    )

    /** A zip written on purpose, with every field the reader is supposed to validate. */
    private fun zip(
        vararg parts: Part,
        comment: String = "",
        diskNumber: Int = 0,
    ): ByteArray {
        val out = Writer()
        val offsets = ArrayList<Long>(parts.size)
        for (part in parts) {
            offsets += out.size().toLong()
            val name = part.name.encodeToByteArray()
            out.u32(LOCAL_SIGNATURE)
            out.u16(20)
            out.u16(part.flags)
            out.u16(part.method)
            out.u16(0)
            out.u16(0)
            out.u32(part.crc)
            out.u32(part.declaredCompressed)
            out.u32(part.declaredUncompressed)
            out.u16(name.size)
            out.u16(part.localExtra.size)
            out.raw(name)
            out.raw(part.localExtra)
            out.raw(part.payload)
        }

        val directoryOffset = out.size().toLong()
        for ((index, part) in parts.withIndex()) {
            val name = part.name.encodeToByteArray()
            out.u32(CENTRAL_SIGNATURE)
            out.u16(20)
            out.u16(20)
            out.u16(part.flags)
            out.u16(part.method)
            out.u16(0)
            out.u16(0)
            out.u32(part.crc)
            out.u32(part.declaredCompressed)
            out.u32(part.declaredUncompressed)
            out.u16(name.size)
            out.u16(part.centralExtra.size)
            out.u16(0)
            out.u16(0)
            out.u16(0)
            out.u32(0)
            out.u32(offsets[index])
            out.raw(name)
            out.raw(part.centralExtra)
        }

        val directorySize = out.size().toLong() - directoryOffset
        val commentBytes = comment.encodeToByteArray()
        out.u32(EOCD_SIGNATURE)
        out.u16(diskNumber)
        out.u16(diskNumber)
        out.u16(parts.size)
        out.u16(parts.size)
        out.u32(directorySize)
        out.u32(directoryOffset)
        out.u16(commentBytes.size)
        out.raw(commentBytes)
        return out.toByteArray()
    }

    private class Writer {
        private val bytes = ArrayList<Byte>(256)

        fun u16(value: Int) {
            bytes.add((value and 0xFF).toByte())
            bytes.add(((value shr 8) and 0xFF).toByte())
        }

        fun u32(value: Long) {
            bytes.add((value and 0xFF).toByte())
            bytes.add(((value shr 8) and 0xFF).toByte())
            bytes.add(((value shr 16) and 0xFF).toByte())
            bytes.add(((value shr 24) and 0xFF).toByte())
        }

        fun raw(data: ByteArray) {
            for (byte in data) bytes.add(byte)
        }

        fun size(): Int = bytes.size

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATE = 8
        const val LOCAL_SIGNATURE = 0x04034b50L
        const val CENTRAL_SIGNATURE = 0x02014b50L
        const val EOCD_SIGNATURE = 0x06054b50L
        const val THREE_GIB = 3L * 1024 * 1024 * 1024

        // zlib.crc32 de Python, para no recalcular con el mismo código que se prueba.
        const val CRC_HOLA = 0x6FA0F988L
        const val CRC_MUNDO = 0x0EB15662L
        const val CRC_UNO = 0x8B084949L
        const val CRC_DOS = 0x9BF6ED00L
    }
}
