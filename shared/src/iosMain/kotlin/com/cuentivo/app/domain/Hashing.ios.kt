package com.cuentivo.app.domain

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/**
 * `CC_SHA256` y `CC_MD5` de CommonCrypto, sobre el buffer que reserva esta función.
 *
 * Con un arreglo vacío se pasa `null` y longitud 0: CommonCrypto calcula entonces el resumen del
 * contenido vacío, igual que `MessageDigest.digest(ByteArray(0))` en Android. Pasar el puntero de un
 * arreglo vacío no es válido.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun digestWith(
    bytes: ByteArray,
    digestLength: Int,
    compute: (data: CValuesRef<*>?, length: UInt, out: CPointer<UByteVar>?) -> Unit,
): ByteArray = memScoped {
    val out = allocArray<UByteVar>(digestLength)
    if (bytes.isEmpty()) {
        compute(null, 0u, out)
    } else {
        bytes.usePinned { pinned ->
            compute(pinned.addressOf(0), bytes.size.convert(), out)
        }
    }
    ByteArray(digestLength) { index -> out[index].toByte() }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray =
    digestWith(bytes, CC_SHA256_DIGEST_LENGTH) { data, length, out -> CC_SHA256(data, length, out) }

@OptIn(ExperimentalForeignApi::class)
internal actual fun md5(bytes: ByteArray): ByteArray =
    digestWith(bytes, CC_MD5_DIGEST_LENGTH) { data, length, out -> CC_MD5(data, length, out) }
