package com.cuentivo.app.domain

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.darwin.COMPRESSION_ZLIB
import platform.darwin.compression_decode_buffer

/**
 * iOS no tiene `ZipInputStream`, así que el zip se lee por el directorio central y se infla con lo
 * que trae el sistema. `COMPRESSION_ZLIB` de Apple es DEFLATE crudo (RFC 1951) —el mismo formato que
 * guarda un ZIP—, no el flujo con cabecera zlib que sugiere el nombre.
 */
internal actual fun readZipEntries(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, ByteArray> =
    ZipCentralDirectory.read(bytes, wanted, ::appleInflateRaw)

/**
 * Infla [compressed] con `compression_decode_buffer` y exige que salga exactamente [uncompressedSize].
 *
 * `scratch_buffer = null` deja que el sistema lo reserve. Se ancla el arreglo de salida, nunca uno
 * vacío: `addressOf(0)` sobre un arreglo de cero bytes truena (lo mismo que cuida `IosDataBridge`).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun appleInflateRaw(compressed: ByteArray, uncompressedSize: Int): ByteArray {
    require(uncompressedSize >= 0) { "Tamaño de salida negativo" }
    if (uncompressedSize == 0) return ByteArray(0)
    require(compressed.isNotEmpty()) { "No hay datos comprimidos que inflar" }

    val inflated = ByteArray(uncompressedSize)
    val written = compressed.usePinned { source ->
        inflated.usePinned { destination ->
            compression_decode_buffer(
                dst_buffer = destination.addressOf(0).reinterpret(),
                dst_size = uncompressedSize.convert(),
                src_buffer = source.addressOf(0),
                src_size = compressed.size.convert(),
                scratch_buffer = null,
                algorithm = COMPRESSION_ZLIB,
            )
        }
    }
    if (written.toLong() != uncompressedSize.toLong()) {
        throw IllegalArgumentException("El bloque comprimido no mide lo que declara")
    }
    return inflated
}
