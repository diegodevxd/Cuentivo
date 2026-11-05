package com.cuentivo.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vectores fijos de SHA-256 y del UUID v3 que usan `SaleOperationIds` / `PaymentOperationIds`.
 *
 * Los valores esperados no se calcularon con el código de este módulo: salen del JDK
 * (`MessageDigest` y `java.util.UUID.nameUUIDFromBytes`), que es exactamente lo que la app ejecutaba
 * antes de moverlo a común. Si una plataforma calcula un hash distinto, esto falla en vez de dejar
 * que un `businessId` o una clave de idempotencia cambien de forma silenciosa.
 */
class HashingCommonTest {

    @Test
    fun sha256_matches_the_standard_vector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hexDigest(sha256("abc".encodeToByteArray())),
        )
    }

    @Test
    fun md5_of_empty_input_matches_the_standard_vector() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hexDigest(md5(ByteArray(0))))
    }

    @Test
    fun name_uuid_v3_matches_java_util_uuid_name_uuid_from_bytes() {
        // java.util.UUID.nameUUIDFromBytes("".getBytes(UTF_8)) en el JDK.
        assertEquals("d41d8cd9-8f00-3204-a980-0998ecf8427e", nameUuidV3(""))
        // java.util.UUID.nameUUIDFromBytes("test".getBytes(UTF_8)) en el JDK.
        assertEquals("098f6bcd-4621-3373-8ade-4e832627b4f6", nameUuidV3("test"))
    }
}
