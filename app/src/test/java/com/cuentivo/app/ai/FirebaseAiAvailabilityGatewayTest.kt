package com.cuentivo.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseAiAvailabilityGatewayTest {

    @Test
    fun `available true in response parses to true`() {
        val response = mapOf("available" to true)
        assertTrue(parseAvailabilityResponse(response))
    }

    @Test
    fun `available false in response parses to false`() {
        val response = mapOf("available" to false)
        assertFalse(parseAvailabilityResponse(response))
    }

    @Test
    fun `null response fails open to true`() {
        assertTrue(parseAvailabilityResponse(null))
    }

    @Test
    fun `malformed response or wrong type fails open to true`() {
        assertTrue(parseAvailabilityResponse("not a map"))
        assertTrue(parseAvailabilityResponse(12345))
        assertTrue(parseAvailabilityResponse(listOf("available", false)))
        assertTrue(parseAvailabilityResponse(emptyMap<String, Any>()))
        assertTrue(parseAvailabilityResponse(mapOf("other_field" to "unexpected")))
    }

    @Test
    fun `fake availability gateway simulates fail open on exception`() {
        val failingGateway = object : AiAvailabilityGateway {
            override suspend fun checkAvailability(): Boolean {
                return try {
                    throw RuntimeException("Network timeout or connection refused")
                } catch (_: Exception) {
                    true
                }
            }
        }
        runBlocking {
            assertTrue(failingGateway.checkAvailability())
        }
    }
}
