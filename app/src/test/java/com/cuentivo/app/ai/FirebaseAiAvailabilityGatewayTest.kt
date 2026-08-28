package com.cuentivo.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseAiAvailabilityGatewayTest {

    @Test
    fun `available true in response parses to true`() {
        val response = mapOf("available" to true)
        assertEquals(true, parseAvailabilityResponse(response))
    }

    @Test
    fun `available false in response parses to false`() {
        val response = mapOf("available" to false)
        assertEquals(false, parseAvailabilityResponse(response))
    }

    @Test
    fun `null response parses to null`() {
        assertNull(parseAvailabilityResponse(null))
    }

    @Test
    fun `malformed response or wrong type parses to null`() {
        assertNull(parseAvailabilityResponse("not a map"))
        assertNull(parseAvailabilityResponse(12345))
        assertNull(parseAvailabilityResponse(listOf("available", false)))
        assertNull(parseAvailabilityResponse(emptyMap<String, Any>()))
        assertNull(parseAvailabilityResponse(mapOf("other_field" to "unexpected")))
    }

    @Test
    fun `fake availability gateway returns null on exception`() {
        val failingGateway = object : AiAvailabilityGateway {
            override suspend fun checkAvailability(): Boolean? {
                return try {
                    throw RuntimeException("Network timeout or connection refused")
                } catch (_: Exception) {
                    null
                }
            }
        }
        runBlocking {
            assertNull(failingGateway.checkAvailability())
        }
    }

    // ---- Policy & TTL tests ----

    @Test
    fun `shouldRefresh policy ignores non-AI tiers unconditionally`() {
        val now = 1_000_000L
        assertFalse(AiAvailabilityPolicy.shouldRefresh(isAiTier = false, lastFetchEpochMillis = 0L, nowEpochMillis = now))
        assertFalse(AiAvailabilityPolicy.shouldRefresh(isAiTier = false, lastFetchEpochMillis = now - 10_000_000L, nowEpochMillis = now))
    }

    @Test
    fun `shouldRefresh policy triggers on cold start and when TTL expires for AI tier`() {
        val now = 1_000_000L
        val ttl = AI_AVAILABILITY_CACHE_TTL_MILLIS

        // Cold start (never fetched)
        assertTrue(AiAvailabilityPolicy.shouldRefresh(isAiTier = true, lastFetchEpochMillis = 0L, nowEpochMillis = now))

        // Recent fetch (within TTL) -> does not refresh
        assertFalse(AiAvailabilityPolicy.shouldRefresh(isAiTier = true, lastFetchEpochMillis = now - 1000L, nowEpochMillis = now))
        assertFalse(AiAvailabilityPolicy.shouldRefresh(isAiTier = true, lastFetchEpochMillis = now - (ttl - 1L), nowEpochMillis = now))

        // TTL expired -> refreshes
        assertTrue(AiAvailabilityPolicy.shouldRefresh(isAiTier = true, lastFetchEpochMillis = now - ttl, nowEpochMillis = now))
        assertTrue(AiAvailabilityPolicy.shouldRefresh(isAiTier = true, lastFetchEpochMillis = now - (ttl + 5000L), nowEpochMillis = now))
    }

    @Test
    fun `failed refresh preserves last known state and never converts true to false`() {
        // Crucial requirement: a network glitch or timeout must not flip true (available) to false (disabled)
        val currentOpen = true
        val failedFetch: Boolean? = null
        val resolved = AiAvailabilityPolicy.resolve(current = currentOpen, fetched = failedFetch)
        assertTrue("A failed refresh must preserve the open state and not convert true to false", resolved)

        // Preserves last known false if service was previously shut down
        val currentDisabled = false
        val resolvedDisabled = AiAvailabilityPolicy.resolve(current = currentDisabled, fetched = failedFetch)
        assertFalse("A failed refresh preserves the last known disabled state", resolvedDisabled)
    }

    @Test
    fun `successful refresh updates state accurately`() {
        // Remote switch turns ON
        assertTrue(AiAvailabilityPolicy.resolve(current = false, fetched = true))
        assertTrue(AiAvailabilityPolicy.resolve(current = true, fetched = true))

        // Remote switch turns OFF
        assertFalse(AiAvailabilityPolicy.resolve(current = true, fetched = false))
        assertFalse(AiAvailabilityPolicy.resolve(current = false, fetched = false))
    }

    @Test
    fun `two successive triggers before response arrives produce exactly one query`() {
        var queryCount = 0
        var lastFetchEpochMillis = 0L
        val isAiTier = true
        val now = 1_000_000L

        // Trigger 1 (e.g. LaunchedEffect on composition)
        if (AiAvailabilityPolicy.shouldRefresh(isAiTier, lastFetchEpochMillis, now)) {
            lastFetchEpochMillis = now
            queryCount++
        }

        // Trigger 2 (e.g. Lifecycle ON_RESUME event replay at startup)
        if (AiAvailabilityPolicy.shouldRefresh(isAiTier, lastFetchEpochMillis, now)) {
            lastFetchEpochMillis = now
            queryCount++
        }

        assertEquals("Two successive triggers without waiting for response must produce only one query", 1, queryCount)
    }
}
