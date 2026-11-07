package com.cuentivo.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncPayloadJsonTest {

    @Test
    fun `quotes are escaped`() {
        assertEquals("""{"a":"x\"y"}""", SyncPayloadJson.encode(mapOf("a" to "x\"y")))
    }

    @Test
    fun `backslashes are escaped`() {
        assertEquals("""{"a":"a\\b"}""", SyncPayloadJson.encode(mapOf("a" to "a\\b")))
    }

    @Test
    fun `newlines are escaped`() {
        assertEquals("""{"a":"a\nb"}""", SyncPayloadJson.encode(mapOf("a" to "a\nb")))
    }

    @Test
    fun `control characters use the unicode escape`() {
        assertEquals("""{"a":"\u0001"}""", SyncPayloadJson.encode(mapOf("a" to "\u0001")))
    }

    @Test
    fun `accents and emoji pass through`() {
        assertEquals("""{"a":"á😀"}""", SyncPayloadJson.encode(mapOf("a" to "á😀")))
    }

    @Test
    fun `null is written as null`() {
        assertEquals("""{"a":null}""", SyncPayloadJson.encode(mapOf("a" to null)))
    }

    @Test
    fun `long max keeps every digit`() {
        assertEquals("""{"a":9223372036854775807}""", SyncPayloadJson.encode(mapOf("a" to Long.MAX_VALUE)))
    }

    @Test
    fun `booleans are written as true and false`() {
        assertEquals("""{"a":true}""", SyncPayloadJson.encode(mapOf("a" to true)))
        assertEquals("""{"a":false}""", SyncPayloadJson.encode(mapOf("a" to false)))
    }

    @Test
    fun `double is rejected with its type in the message`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SyncPayloadJson.encode(mapOf("a" to 1.0))
        }
        assertEquals("Tipo no admitido en payload: Double", error.message)
    }

    @Test
    fun `float is rejected with its type in the message`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SyncPayloadJson.encode(mapOf("a" to 1.0f))
        }
        assertEquals("Tipo no admitido en payload: Float", error.message)
    }
}
