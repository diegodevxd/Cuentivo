package com.cuentivo.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * El inflador de iOS, contra un DEFLATE crudo de verdad.
 *
 * Los bytes salen de `zlib.compressobj(9, zlib.DEFLATED, -15)` en Python: `-15` es exactamente
 * DEFLATE crudo (RFC 1951), sin cabecera zlib, que es lo que guarda un ZIP y lo que espera
 * `COMPRESSION_ZLIB` de Apple.
 *
 * Pendiente de CI: esta prueba sólo corre en `:shared:iosSimulatorArm64Test`.
 */
class AppleInflateRawTest {

    @Test
    fun `infla un bloque DEFLATE crudo conocido`() {
        val inflated = appleInflateRaw(KNOWN_DEFLATE, KNOWN_TEXT_SIZE)

        assertEquals(KNOWN_TEXT, inflated.decodeToString())
    }

    @Test
    fun `basura que no es DEFLATE lanza`() {
        assertFails { appleInflateRaw(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 10) }
    }

    @Test
    fun `un tamaño equivocado lanza`() {
        // Pedir menos de lo que mide el texto: la salida no cabe y `compression_decode_buffer`
        // devuelve 0 en vez de truncar.
        assertFails { appleInflateRaw(KNOWN_DEFLATE, KNOWN_TEXT_SIZE + 10) }
    }

    private companion object {
        const val KNOWN_TEXT = "hola desde iOS, con acentos: cancion"
        const val KNOWN_TEXT_SIZE = 36

        val KNOWN_DEFLATE = byteArrayOf(
            203.toByte(), 200.toByte(), 207.toByte(), 73, 84, 72, 73, 45, 78, 73, 85,
            200.toByte(), 244.toByte(), 15, 214.toByte(), 81, 72, 206.toByte(), 207.toByte(),
            83, 72, 76, 78, 205.toByte(), 43, 201.toByte(), 47, 182.toByte(), 82, 72, 78,
            204.toByte(), 75, 206.toByte(), 204.toByte(), 207.toByte(), 3, 0,
        )
    }
}
