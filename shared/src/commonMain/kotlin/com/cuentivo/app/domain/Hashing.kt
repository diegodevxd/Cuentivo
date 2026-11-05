package com.cuentivo.app.domain

/**
 * Los dos resúmenes que el dominio necesita, sin `java.security`.
 *
 * Cada plataforma usa su implementación nativa (Android: `MessageDigest`; iOS: CommonCrypto). Los
 * dígitos hexadecimales resultantes son los mismos en ambas: de esto depende que un
 * `obfuscatedAccountId` y una clave de idempotencia no cambien al pasar de Android a iOS.
 */

/** SHA-256 de [bytes]. 32 bytes crudos. */
internal expect fun sha256(bytes: ByteArray): ByteArray

/**
 * MD5 de [bytes].
 *
 * No se usa para nada de seguridad: existe sólo porque `java.util.UUID.nameUUIDFromBytes` (UUID v3)
 * es MD5 sobre el nombre, y los ids de operación ya guardados en `operations` se calcularon con él.
 * Cambiar de algoritmo cambiaría esos ids y con ellos la idempotencia ya persistida.
 */
internal expect fun md5(bytes: ByteArray): ByteArray

/** [bytes] en minúsculas hexadecimales, dos dígitos por byte. */
internal fun hexDigest(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xff
        out.append(digits[value ushr 4]).append(digits[value and 0x0f])
    }
    return out.toString()
}

/**
 * El mismo id que `java.util.UUID.nameUUIDFromBytes(name.toByteArray()).toString()`.
 *
 * Es el UUID v3 que usan `SaleOperationIds` y `PaymentOperationIds`: MD5 del nombre, con los bits de
 * versión (3) y de variante (RFC 4122) sobreescritos en los bytes 6 y 8. Se reimplementa en común
 * porque `kotlin.uuid.Uuid` sólo expone la construcción desde bytes, no el nombre con MD5.
 */
internal fun nameUuidV3(name: String): String {
    val digest = md5(name.encodeToByteArray())
    digest[6] = ((digest[6].toInt() and 0x0f) or 0x30).toByte()
    digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = hexDigest(digest)
    return buildString(36) {
        append(hex, 0, 8).append('-')
        append(hex, 8, 12).append('-')
        append(hex, 12, 16).append('-')
        append(hex, 16, 20).append('-')
        append(hex, 20, 32)
    }
}
