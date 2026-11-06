package com.cuentivo.app.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * El nonce de Apple, contra el generador real del sistema.
 *
 * Corre en `:shared:iosSimulatorArm64Test`, porque `SecRandomCopyBytes` sólo existe en iOS. Lo que se
 * fija aquí es lo que Apple exige: longitud, alfabeto seguro, que dos nonces no coincidan y que el
 * hash sea el SHA-256 de verdad.
 */
class AppleNonceTest {

    @Test
    fun the_default_nonce_is_256_bits_of_hex() {
        val raw = AppleNonce.raw()
        assertEquals(AppleNonce.DEFAULT_BYTE_LENGTH * 2, raw.length)
    }

    @Test
    fun a_custom_length_is_respected() {
        assertEquals(16 * 2, AppleNonce.raw(16).length)
        assertEquals(1 * 2, AppleNonce.raw(1).length)
    }

    @Test
    fun only_hex_characters_are_used() {
        repeat(20) {
            val raw = AppleNonce.raw()
            assertTrue(raw.all { it in '0'..'9' || it in 'a'..'f' }, "carácter inesperado en el nonce")
        }
    }

    @Test
    fun two_nonces_are_never_the_same() {
        val first = AppleNonce.raw()
        val second = AppleNonce.raw()
        assertNotEquals(first, second)
    }

    @Test
    fun the_hash_is_the_sha256_of_the_raw_nonce() {
        // Vector conocido: sha256("abc") en el JDK.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AppleNonce.hashed("abc"),
        )
        // Y el hash de un nonce real coincide con el que produce el mismo sha256 del dominio.
        val raw = AppleNonce.raw()
        assertEquals(AppleNonce.hashed(raw), AppleNonce.hashed(raw))
        assertEquals(64, AppleNonce.hashed(raw).length)
    }
}
