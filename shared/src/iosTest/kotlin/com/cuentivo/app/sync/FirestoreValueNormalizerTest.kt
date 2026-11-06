package com.cuentivo.app.sync

import platform.Foundation.NSNull
import platform.Foundation.NSNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pruebas del normalizador en el simulador. Invocan las funciones reales de producción, no una copia
 * de la regla: si el normalizador empieza a truncar, esto se pone en rojo.
 *
 * Corre con `:shared:iosSimulatorArm64Test` en el runner macOS.
 */
class FirestoreValueNormalizerTest {

    @Test
    fun a_long_long_number_stays_an_exact_long() {
        val normalized = normalizeFirestoreValue(NSNumber.numberWithLongLong(9007199254740993L))
        assertEquals(9007199254740993L, normalized)
        assertTrue(normalized is Long)
    }

    @Test
    fun a_decimal_does_not_become_a_long() {
        val normalized = normalizeFirestoreValue(NSNumber.numberWithDouble(10.5))
        assertEquals(10.5, normalized)
        assertTrue(normalized is Double)
    }

    @Test
    fun a_whole_double_stays_a_double() {
        // 10.0 es Double en Firestore; convertirlo a Long escondería que el campo no es un centavo.
        val normalized = normalizeFirestoreValue(NSNumber.numberWithDouble(10.0))
        assertEquals(10.0, normalized)
        assertTrue(normalized is Double)
    }

    @Test
    fun a_boolean_is_a_boolean_and_an_integer_one_is_not() {
        assertEquals(true, normalizeFirestoreValue(NSNumber.numberWithBool(true)))
        assertEquals(false, normalizeFirestoreValue(NSNumber.numberWithBool(false)))

        // El caso que la frontera prohíbe: un entero 1 no puede volverse `true`.
        val one = normalizeFirestoreValue(NSNumber.numberWithLongLong(1L))
        assertEquals(1L, one)
        assertTrue(one is Long)
    }

    @Test
    fun strings_null_and_nested_collections_are_converted() {
        assertEquals("Rosa", normalizeFirestoreValue("Rosa"))
        assertEquals(null, normalizeFirestoreValue(NSNull()))

        val nested = mapOf<Any?, Any?>(
            "customer" to mapOf<Any?, Any?>("name" to "Rosa", "balance" to NSNumber.numberWithLongLong(1500L)),
            "items" to listOf(NSNumber.numberWithLongLong(1L), NSNumber.numberWithDouble(2.5)),
            "flag" to NSNumber.numberWithBool(true),
        )
        @Suppress("UNCHECKED_CAST")
        val normalized = normalizeFirestoreValue(nested) as Map<Any?, Any?>
        @Suppress("UNCHECKED_CAST")
        val customer = normalized["customer"] as Map<Any?, Any?>
        assertEquals("Rosa", customer["name"])
        assertEquals(1500L, customer["balance"])
        @Suppress("UNCHECKED_CAST")
        val items = normalized["items"] as List<Any?>
        assertEquals(1L, items[0])
        assertEquals(2.5, items[1])
        assertEquals(true, normalized["flag"])
    }

    @Test
    fun a_json_payload_keeps_integers_decimals_booleans_and_nulls() {
        val map = parseJsonToFirestoreMap(
            """{"totalCents": 1500, "big": 9007199254740993, "ratio": 0.5, "paid": true, "note": null,
               "sale": {"items": [{"q": 2}]}}""",
        )
        assertEquals(1500L, map["totalCents"])
        assertEquals(9007199254740993L, map["big"])
        assertEquals(0.5, map["ratio"])
        assertEquals(true, map["paid"])
        assertTrue(map.containsKey("note"))
        assertEquals(null, map["note"])
        @Suppress("UNCHECKED_CAST")
        val sale = map["sale"] as Map<Any?, Any?>
        @Suppress("UNCHECKED_CAST")
        val items = sale["items"] as List<Map<Any?, Any?>>
        assertEquals(2L, items[0]["q"])
    }
}
