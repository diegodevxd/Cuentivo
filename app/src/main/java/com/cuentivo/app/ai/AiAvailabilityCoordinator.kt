package com.cuentivo.app.ai

/**
 * Coordinates in-memory AI availability state, managing TTL caching and deduplication.
 */
class AiAvailabilityCoordinator(
    private val gateway: AiAvailabilityGateway,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val ttlMillis: Long = AI_AVAILABILITY_CACHE_TTL_MILLIS,
) {
    var isAvailable: Boolean = true
        private set

    var lastFetchEpochMillis: Long = 0L
        private set

    /**
     * Resets state to default open when navigating away from AI tier.
     */
    fun reset() {
        isAvailable = true
        lastFetchEpochMillis = 0L
    }

    /**
     * Refreshes availability if the user is on the AI tier and TTL has expired.
     * Stamps [lastFetchEpochMillis] BEFORE calling the gateway to deduplicate concurrent triggers
     * (e.g. LaunchedEffect on composition and Lifecycle ON_RESUME event replay at startup).
     */
    suspend fun refresh(isAiTier: Boolean): Boolean {
        val now = timeProvider()
        if (!AiAvailabilityPolicy.shouldRefresh(isAiTier, lastFetchEpochMillis, now, ttlMillis)) {
            return isAvailable
        }
        lastFetchEpochMillis = now
        val response = gateway.checkAvailability()
        isAvailable = AiAvailabilityPolicy.resolve(isAvailable, response)
        return isAvailable
    }
}
