package com.cuentivo.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseAiAvailabilityGatewayTest {

    private class CountingAiAvailabilityGateway(
        var response: Boolean? = true,
    ) : AiAvailabilityGateway {
        var callCount = 0
            private set

        override suspend fun checkAvailability(): Boolean? {
            callCount++
            return response
        }
    }

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

    // ---- Coordinator orchestrator tests ----

    @Test
    fun `coordinator deduplicates successive triggers at startup and queries gateway exactly once`() = runBlocking {
        val fakeGateway = CountingAiAvailabilityGateway(response = true)
        val currentTime = 1_000_000L
        val coordinator = AiAvailabilityCoordinator(
            gateway = fakeGateway,
            timeProvider = { currentTime },
        )

        // First trigger (LaunchedEffect on composition entry)
        val firstResult = coordinator.refresh(isAiTier = true)

        // Second trigger (Lifecycle ON_RESUME event replay at startup)
        val secondResult = coordinator.refresh(isAiTier = true)

        assertEquals("First call must succeed", true, firstResult)
        assertEquals("Second call must succeed without re-fetching", true, secondResult)
        assertEquals("Gateway must be called exactly once across successive triggers", 1, fakeGateway.callCount)
    }

    @Test
    fun `coordinator respects TTL and only refreshes when expired`() = runBlocking {
        val fakeGateway = CountingAiAvailabilityGateway(response = true)
        var currentTime = 1_000_000L
        val coordinator = AiAvailabilityCoordinator(
            gateway = fakeGateway,
            timeProvider = { currentTime },
        )

        coordinator.refresh(isAiTier = true)
        assertEquals(1, fakeGateway.callCount)

        // 1 minute later (TTL not expired)
        currentTime += 60_000L
        coordinator.refresh(isAiTier = true)
        assertEquals("Must not query gateway when TTL is active", 1, fakeGateway.callCount)

        // 5 minutes later (TTL expired)
        currentTime += AI_AVAILABILITY_CACHE_TTL_MILLIS
        coordinator.refresh(isAiTier = true)
        assertEquals("Must query gateway when TTL expires", 2, fakeGateway.callCount)
    }

    @Test
    fun `coordinator ignores non-AI tiers`() = runBlocking {
        val fakeGateway = CountingAiAvailabilityGateway(response = true)
        val coordinator = AiAvailabilityCoordinator(gateway = fakeGateway)

        coordinator.refresh(isAiTier = false)

        assertEquals(0, fakeGateway.callCount)
        assertTrue(coordinator.isAvailable)
    }

    @Test
    fun `coordinator preserves true on network failure and does not close entrance`() = runBlocking {
        val failingGateway = CountingAiAvailabilityGateway(response = null)
        val coordinator = AiAvailabilityCoordinator(gateway = failingGateway)

        val result = coordinator.refresh(isAiTier = true)

        assertEquals("Gateway must have been invoked", 1, failingGateway.callCount)
        assertTrue("Failed refresh must preserve open state (true)", result)
        assertTrue("Coordinator property must remain true", coordinator.isAvailable)
    }

    @Test
    fun `coordinator reset restores initial state`() = runBlocking {
        val fakeGateway = CountingAiAvailabilityGateway(response = false)
        val coordinator = AiAvailabilityCoordinator(gateway = fakeGateway)

        coordinator.refresh(isAiTier = true)
        assertFalse(coordinator.isAvailable)

        coordinator.reset()
        assertTrue(coordinator.isAvailable)
        assertEquals(0L, coordinator.lastFetchEpochMillis)
    }
}
