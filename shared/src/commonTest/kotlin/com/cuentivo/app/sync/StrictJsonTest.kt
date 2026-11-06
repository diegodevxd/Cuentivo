package com.cuentivo.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * El parser que alimenta `parseJsonToFirestoreMap` en iOS. Se prueba aquí, en la JVM del host,
 * porque es lo único de la frontera de Firestore que se puede verificar sin una Mac.
 */
class StrictJsonTest {

    @Test
    fun integers_stay_exact_even_above_two_to_the_53() {
        val map = StrictJson.parseObject("""{"big": 9007199254740993, "small": 42, "negative": -7}""")
        assertEquals(9007199254740993L, map["big"])
        assertEquals(42L, map["small"])
        assertEquals(-7L, map["negative"])
    }

    @Test
    fun decimals_stay_double_and_are_not_truncated() {
        val map = StrictJson.parseObject("""{"amount": 10.5, "exponent": 1e3, "whole": 10.0}""")
        assertEquals(10.5, map["amount"])
        assertEquals(1000.0, map["exponent"])
        assertEquals(10.0, map["whole"])
        assertTrue(map["whole"] is Double)
    }

    @Test
    fun booleans_nulls_and_strings_keep_their_types() {
        val map = StrictJson.parseObject("""{"flag": true, "off": false, "none": null, "name": "Rosa"}""")
        assertEquals(true, map["flag"])
        assertEquals(false, map["off"])
        assertEquals(null, map["none"])
        assertTrue(map.containsKey("none"))
        assertEquals("Rosa", map["name"])
    }

    @Test
    fun nested_objects_and_arrays_are_parsed() {
        val map = StrictJson.parseObject(
            """{"sale":{"id":"s-1","total":1500,"items":[{"p":"Crema","q":2},{"p":"Jabon","q":1}]}}""",
        )
        @Suppress("UNCHECKED_CAST")
        val sale = map["sale"] as Map<String, Any?>
        assertEquals("s-1", sale["id"])
        assertEquals(1500L, sale["total"])
        @Suppress("UNCHECKED_CAST")
        val items = sale["items"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
        assertEquals("Jabon", items[1]["p"])
        assertEquals(1L, items[1]["q"])
    }

    @Test
    fun escapes_are_decoded() {
        val map = StrictJson.parseObject("""{"note":"linea\nnueva \"con comillas\" y \\ barra"}""")
        assertEquals("linea\nnueva \"con comillas\" y \\ barra", map["note"])
    }

    @Test
    fun an_integer_that_does_not_fit_is_rejected_instead_of_rounded() {
        assertFailsWith<IllegalArgumentException> {
            StrictJson.parseObject("""{"big": 9223372036854775808}""")
        }
    }

    @Test
    fun malformed_input_is_rejected() {
        assertFailsWith<IllegalArgumentException> { StrictJson.parseObject("{") }
        assertFailsWith<IllegalArgumentException> { StrictJson.parseObject("""{"a":1} extra""") }
        assertFailsWith<IllegalArgumentException> { StrictJson.parseObject("""[1,2]""") }
    }
}
