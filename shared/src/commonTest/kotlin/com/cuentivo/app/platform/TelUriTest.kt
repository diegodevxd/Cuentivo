package com.cuentivo.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * El `tel:` que se le entrega al sistema.
 *
 * Se prueba la función real: si mañana alguien deja pasar un espacio o un guion, esto falla.
 */
class TelUriTest {

    @Test
    fun `conserva los digitos de un numero con formato`() {
        assertEquals("+525512345678", TelUri.from("+52 (55) 1234-5678"))
    }

    @Test
    fun `un numero sin lada queda igual`() {
        assertEquals("5512345678", TelUri.from("5512345678"))
    }

    @Test
    fun `sin digitos no hay telefono`() {
        assertNull(TelUri.from(""))
        assertNull(TelUri.from("   "))
        assertNull(TelUri.from("sin numero"))
        assertNull(TelUri.from("+"))
    }

    @Test
    fun `el mas solo cuenta al principio`() {
        assertEquals("+15551234567", TelUri.from("+1 555 123 4567"))
        assertEquals("15551234567", TelUri.from("1 555 123 4567+"))
    }
}
