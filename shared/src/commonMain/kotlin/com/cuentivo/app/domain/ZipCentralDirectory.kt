package com.cuentivo.app.domain

/**
 * Reads a zip through its **central directory**, which is the only place that knows the real sizes.
 *
 * `ZipInputStream` is not available in Kotlin/Native, so iOS can't use it. Reading the central
 * directory instead of the local headers is not a shortcut, it is the correct end: a file written to
 * a non-seekable stream (an Excel export saved straight to a cloud drive, for instance) leaves the
 * sizes at zero in the local header and puts them in a trailing data descriptor. The central
 * directory always has them, because it is written last.
 *
 * The inflater is injected on purpose: production passes the system one (Apple's
 * `compression_decode_buffer` on iOS, `Inflater` on Android's shared tests), and the tests pass a
 * JVM one so this file's parsing can be compared against `ZipInputStream` on Windows.
 *
 * Everything is validated before a single byte is reserved: a truncated or hostile file must fail
 * with an exception, never by allocating gigabytes first.
 */
internal object ZipCentralDirectory {

    /** Reads the entries [wanted] accepts; the rest are never inflated. */
    fun read(
        bytes: ByteArray,
        wanted: (String) -> Boolean,
        inflateRaw: (compressed: ByteArray, uncompressedSize: Int) -> ByteArray,
    ): Map<String, ByteArray> {
        val eocd = findEndOfCentralDirectory(bytes)
            ?: return if (startsWithLocalHeader(bytes)) {
                throw IllegalArgumentException("El ZIP está truncado: no tiene fin de directorio")
            } else {
                // Same answer ZipInputStream gives today for something that is not a zip at all: no
                // entries, no exception. A CSV arrives here.
                emptyMap()
            }

        val entryCount = u16(bytes, eocd + 10)
        val directorySize = u32(bytes, eocd + 12)
        val directoryOffset = u32(bytes, eocd + 16)

        if (u16(bytes, eocd + 4) != 0 || u16(bytes, eocd + 6) != 0 || u16(bytes, eocd + 8) != entryCount) {
            throw IllegalArgumentException("ZIP multidisco: no se puede leer")
        }
        if (entryCount == ZIP64_U16 || directorySize == ZIP64_U32 || directoryOffset == ZIP64_U32) {
            throw IllegalArgumentException("ZIP64: no se puede leer")
        }
        if (directoryOffset + directorySize > bytes.size.toLong() || directoryOffset + directorySize > eocd) {
            throw IllegalArgumentException("El ZIP está truncado: el directorio se sale del archivo")
        }

        val requested = ArrayList<Requested>(entryCount)
        var requestedBytes = 0L
        var cursor = directoryOffset.toInt()

        repeat(entryCount) {
            val entry = readCentralEntry(bytes, cursor)
            cursor = entry.next
            if (entry.directory || !wanted(entry.name)) return@repeat
            // Both limits are applied here, after `wanted` and before any payload is copied, so a
            // workbook with a huge image next to its tiny XML is still readable.
            if (entry.uncompressedSize > MAX_ENTRY_BYTES) {
                throw IllegalArgumentException("Una entrada del ZIP declara ${entry.uncompressedSize} bytes")
            }
            requestedBytes += entry.uncompressedSize
            if (requestedBytes > MAX_TOTAL_BYTES) {
                throw IllegalArgumentException("El ZIP pide más de $MAX_TOTAL_BYTES bytes en total")
            }
            requested += entry
        }

        val entries = LinkedHashMap<String, ByteArray>(requested.size)
        for (entry in requested) {
            // The last one wins, exactly like a map filled in directory order by ZipInputStream.
            entries[entry.name] = entry.readData(bytes, inflateRaw)
        }
        return entries
    }

    private fun readCentralEntry(bytes: ByteArray, at: Int): Requested {
        if (u32(bytes, at) != CENTRAL_SIGNATURE) {
            throw IllegalArgumentException("El directorio central del ZIP está dañado")
        }
        val flags = u16(bytes, at + 8)
        val method = u16(bytes, at + 10)
        val crc = u32(bytes, at + 16)
        val compressedSize = u32(bytes, at + 20)
        val uncompressedSize = u32(bytes, at + 24)
        val nameLength = u16(bytes, at + 28)
        val extraLength = u16(bytes, at + 30)
        val commentLength = u16(bytes, at + 32)
        val localOffset = u32(bytes, at + 42)

        if (compressedSize == ZIP64_U32 || uncompressedSize == ZIP64_U32 ||
            localOffset == ZIP64_U32 || nameLength == ZIP64_U16 ||
            extraLength == ZIP64_U16 || commentLength == ZIP64_U16
        ) {
            throw IllegalArgumentException("ZIP64: no se puede leer")
        }
        // Bit 0 is traditional encryption. There is no password anywhere in this flow, so an
        // encrypted entry is a file we cannot read, not one worth failing halfway through.
        if (flags and FLAG_ENCRYPTED != 0) {
            throw IllegalArgumentException("El ZIP está cifrado: no se puede leer")
        }
        if (method != METHOD_STORED && method != METHOD_DEFLATE) {
            throw IllegalArgumentException("Método de compresión $method no soportado")
        }

        val nameStart = at + CENTRAL_HEADER
        val nameEnd = nameStart + nameLength
        if (nameEnd + extraLength + commentLength > bytes.size) {
            throw IllegalArgumentException("El directorio central del ZIP está truncado")
        }
        val name = bytes.decodeToString(nameStart, nameEnd)

        return Requested(
            name = name,
            directory = name.endsWith("/"),
            method = method,
            crc = crc,
            compressedSize = compressedSize,
            uncompressedSize = uncompressedSize,
            localOffset = localOffset,
            next = nameEnd + extraLength + commentLength,
        )
    }

    private class Requested(
        val name: String,
        val directory: Boolean,
        private val method: Int,
        private val crc: Long,
        private val compressedSize: Long,
        val uncompressedSize: Long,
        private val localOffset: Long,
        val next: Int,
    ) {
        fun readData(
            bytes: ByteArray,
            inflateRaw: (compressed: ByteArray, uncompressedSize: Int) -> ByteArray,
        ): ByteArray {
            if (u32(bytes, localOffset.toInt()) != LOCAL_SIGNATURE) {
                throw IllegalArgumentException("El encabezado local de $name está dañado")
            }
            // The local extra field is usually a different length than the central one; taking it
            // from the directory would read seven or more bytes of the payload as if they were part
            // of the header.
            val localNameLength = u16(bytes, localOffset.toInt() + 26)
            val localExtraLength = u16(bytes, localOffset.toInt() + 28)
            val dataStart = localOffset + LOCAL_HEADER + localNameLength + localExtraLength
            if (dataStart + compressedSize > bytes.size) {
                throw IllegalArgumentException("El ZIP está truncado: faltan datos de $name")
            }

            val start = dataStart.toInt()
            val end = (dataStart + compressedSize).toInt()
            val data = when {
                uncompressedSize == 0L -> ByteArray(0)
                compressedSize == 0L ->
                    throw IllegalArgumentException("La entrada $name no trae datos comprimidos")
                method == METHOD_STORED -> {
                    if (compressedSize != uncompressedSize) {
                        throw IllegalArgumentException("La entrada $name declara tamaños distintos sin comprimir")
                    }
                    bytes.copyOfRange(start, end)
                }
                else -> {
                    val inflated = inflateRaw(bytes.copyOfRange(start, end), uncompressedSize.toInt())
                    if (inflated.size.toLong() != uncompressedSize) {
                        throw IllegalArgumentException("La entrada $name no mide lo que declara")
                    }
                    inflated
                }
            }
            if (crc32(data) != crc) {
                throw IllegalArgumentException("La entrada $name no coincide con su CRC")
            }
            return data
        }
    }

    /**
     * The end of central directory, if there is one.
     *
     * It is searched from the end because a comment may contain the signature, and the length of
     * that comment is what proves the record found is the real one: it has to land exactly on the
     * last byte.
     */
    private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
        if (bytes.size < EOCD_MIN) return null
        val earliest = (bytes.size - EOCD_MIN - MAX_COMMENT).coerceAtLeast(0)
        var at = bytes.size - EOCD_MIN
        while (at >= earliest) {
            if (u32OrNull(bytes, at) == EOCD_SIGNATURE &&
                at + EOCD_MIN + u16(bytes, at + 20) == bytes.size
            ) {
                return at
            }
            at--
        }
        return null
    }

    private fun startsWithLocalHeader(bytes: ByteArray): Boolean =
        bytes.size >= 4 && u32OrNull(bytes, 0) == LOCAL_SIGNATURE

    private fun u16(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at + 2 > bytes.size) throw IllegalArgumentException("El ZIP está truncado")
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > bytes.size) throw IllegalArgumentException("El ZIP está truncado")
        return u32OrNull(bytes, at)!!
    }

    private fun u32OrNull(bytes: ByteArray, at: Int): Long? {
        if (at < 0 || at + 4 > bytes.size) return null
        return (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)
    }

    /** CRC-32 (IEEE 802.3), the checksum zip stores. Table built once, in pure Kotlin. */
    private fun crc32(data: ByteArray): Long {
        var crc = -1
        for (byte in data) {
            crc = (crc ushr 8) xor CRC_TABLE[(crc xor byte.toInt()) and 0xFF]
        }
        return crc.inv().toLong() and 0xFFFFFFFFL
    }

    private val CRC_TABLE = IntArray(256) { index ->
        var value = index
        repeat(8) {
            value = if (value and 1 != 0) (value ushr 1) xor 0xEDB88320.toInt() else value ushr 1
        }
        value
    }

    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val LOCAL_HEADER = 30
    private const val CENTRAL_HEADER = 46
    private const val EOCD_MIN = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val ZIP64_U16 = 0xFFFF
    private const val ZIP64_U32 = 0xFFFFFFFFL
    private const val FLAG_ENCRYPTED = 0x0001
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATE = 8

    /** A single entry may not ask for more than this. */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** Everything `wanted` accepts, together, may not ask for more than this. */
    private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
}
