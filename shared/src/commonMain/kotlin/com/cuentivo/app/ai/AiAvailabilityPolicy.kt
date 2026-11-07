package com.cuentivo.app.ai

/**
 * Cache TTL for remote AI availability check: 5 minutes.
 *
 * Rationale:
 * 1. Entering and exiting the app rapidly (e.g. copying text from WhatsApp or calculator)
 *    must not spam the getAiAvailability callable on every foreground switch.
 * 2. In an emergency killswitch scenario, active client sessions will converge within at most
 *    5 minutes on foreground resume (and immediately on launch or tier switch).
 *    Furthermore, any actual action execution during that window is authoritatively cut
 *    by the server-side authorizer in 0 seconds with AI_DISABLED.
 * 3. 5 minutes balances minimal server cost with timely client UI synchronization.
 */
const val AI_AVAILABILITY_CACHE_TTL_MILLIS: Long = 5 * 60 * 1000L

object AiAvailabilityPolicy {

    /**
     * Determines whether a refresh should be attempted based on subscription tier and elapsed time.
     */
    fun shouldRefresh(
        isAiTier: Boolean,
        lastFetchEpochMillis: Long,
        nowEpochMillis: Long,
        ttlMillis: Long = AI_AVAILABILITY_CACHE_TTL_MILLIS,
    ): Boolean {
        if (!isAiTier) return false
        if (lastFetchEpochMillis == 0L) return true
        return (nowEpochMillis - lastFetchEpochMillis) >= ttlMillis
    }

    /**
     * Resolves availability state after a fetch.
     *
     * Fail-open design (deliberate):
     * If [fetched] is null (network failure or timeout), the [current] state is preserved
     * (or opens to true if initial). A failed refresh NEVER converts true to false.
     * The server-side authorization remains authoritative and fails closed.
     */
    fun resolve(current: Boolean, fetched: Boolean?): Boolean {
        return fetched ?: current
    }
}
