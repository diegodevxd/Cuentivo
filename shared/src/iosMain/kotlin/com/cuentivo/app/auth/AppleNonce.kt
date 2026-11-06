package com.cuentivo.app.auth

import com.cuentivo.app.domain.hexDigest
import com.cuentivo.app.domain.sha256
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.Security.kSecSuccess

/**
 * El nonce de Sign in with Apple.
 *
 * Apple exige que el `nonce` que viaja en la petición sea el SHA-256 del `rawNonce` que después se
 * manda a Firebase. El `rawNonce` **nunca** se imprime ni se registra: es un secreto de un solo uso.
 *
 * Se generan bytes con `SecRandomCopyBytes` (el generador del sistema, criptográficamente seguro) y
 * no con `kotlin.random`, que es un PRNG sin garantía criptográfica y haría adivinable el nonce.
 * La codificación es hexadecimal en minúsculas: sólo `0-9a-f`, que Apple acepta y que sobrevive a
 * cualquier transporte sin escapes.
 */
object AppleNonce {

    /** Bytes aleatorios por defecto. 32 bytes = 256 bits, el tamaño habitual para un nonce. */
    const val DEFAULT_BYTE_LENGTH = 32

    /** Un `rawNonce` nuevo, en hexadecimal minúsculas (`length * 2` caracteres). */
    @OptIn(ExperimentalForeignApi::class)
    fun raw(length: Int = DEFAULT_BYTE_LENGTH): String {
        require(length > 0) { "The nonce needs at least one byte" }
        val bytes = ByteArray(length)
        val status = bytes.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, length.toULong(), pinned.addressOf(0))
        }
        check(status == kSecSuccess) { "The system could not produce secure random bytes" }
        return hexDigest(bytes)
    }

    /** El valor que se manda a Apple: SHA-256 del [rawNonce], en hexadecimal. */
    fun hashed(rawNonce: String): String = hexDigest(sha256(rawNonce.encodeToByteArray()))
}
