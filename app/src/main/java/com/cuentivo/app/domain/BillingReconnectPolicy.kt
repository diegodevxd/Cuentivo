package com.cuentivo.app.domain

/**
 * When to try Google Play again after it hangs up.
 *
 * Play disconnects the billing service as a matter of course — the Play Store updates itself, the
 * process gets trimmed in the background, the device sleeps. That is not an error state, it is the
 * normal life of the connection. What *was* an error is doing nothing about it: a gateway that
 * never reconnects reports "las suscripciones no están disponibles" forever after the first
 * disconnect, and someone trying to pay reads that as the app being broken.
 *
 * Reconnecting immediately and forever is the other failure: on a phone with no Play services at
 * all, a tight loop burns battery to keep being told no.
 *
 * So: exponential backoff, capped both in how long it waits and in how many times it tries. The
 * decision is pure and lives here; [com.cuentivo.app.billing.BillingGateway] only obeys it.
 */
object BillingReconnectPolicy {

    /** Short enough that a routine disconnect is invisible to someone mid-purchase. */
    const val FIRST_DELAY_MILLIS = 1_000L

    /** Past this, waiting longer buys nothing: whatever is wrong is not about to fix itself. */
    const val MAX_DELAY_MILLIS = 30_000L

    /**
     * Roughly a minute and a half of trying, all told.
     *
     * Giving up is not the same as staying broken: [com.cuentivo.app.billing.BillingGateway.start]
     * runs again on the next sign-in, and `onResume` asks for a refresh every time the app comes
     * back to the foreground. So the person who reopens the app gets a fresh set of attempts.
     */
    const val MAX_ATTEMPTS = 6

    /**
     * How long to wait before attempt number [attempt], or `null` to stop trying.
     *
     * @param attempt 1 for the first retry after a disconnect, incrementing while they keep failing.
     *   Reset to zero on every successful connection, so a flaky hour does not exhaust the budget
     *   of the connection that finally works.
     */
    fun delayMillisForAttempt(attempt: Int): Long? {
        if (attempt < 1 || attempt > MAX_ATTEMPTS) return null
        // Doubling in Long space, then clamped — 2^attempt on an Int would overflow long before
        // MAX_ATTEMPTS ever could, and a negative delay would schedule in the past.
        val doubled = FIRST_DELAY_MILLIS shl (attempt - 1)
        return if (doubled > MAX_DELAY_MILLIS) MAX_DELAY_MILLIS else doubled
    }
}
