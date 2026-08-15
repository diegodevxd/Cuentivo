package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the decision a deletion *request* may make — the pure half of the idempotent flag write.
 *
 * The rules make `deletionRequestedAtEpochMillis` immutable once set
 * (`deletionFlagImmutable`), so a request that rewrites it with a fresh timestamp on a retry is
 * denied, and an account whose first attempt failed partway can never resume. The fix is a
 * server-backed read followed by this decision: only an absent flag may be written, a present flag
 * means "already requested" (succeed without writing), and a missing business document means a
 * prior cascade already finished the erasure.
 */
class DeletionRequestPolicyTest {

    /** The marker a first request would have written; a retry must never replace it. */
    private val originalMarker = 1_752_000_000_000L

    @Test
    fun `first request on an existing business writes the flag`() {
        // RED→GREEN: the retried request must not collide with this one.
        assertEquals(
            DeletionRequestPolicy.Outcome.NeedsWrite,
            DeletionRequestPolicy.decide(exists = true, deletionRequestedAtEpochMillis = null),
        )
    }

    @Test
    fun `retried request keeps the original marker and writes nothing`() {
        // A retry sees the flag already on the server. The outcome carries the ORIGINAL timestamp —
        // not a fresh one — and the gateway writes nothing on this branch, so the immutable flag is
        // preserved and the retry is accepted instead of denied.
        assertEquals(
            DeletionRequestPolicy.Outcome.AlreadyRequested(originalMarker),
            DeletionRequestPolicy.decide(exists = true, deletionRequestedAtEpochMillis = originalMarker),
        )
    }

    @Test
    fun `a missing business document counts as already erased`() {
        // A prior cascade deletes the business document last, so its absence means the erasure is
        // already complete; a retry treats it as success instead of trying to write to nothing.
        assertEquals(
            DeletionRequestPolicy.Outcome.AlreadyGone,
            DeletionRequestPolicy.decide(exists = false, deletionRequestedAtEpochMillis = null),
        )
        // Even a stale cached flag value cannot resurrect a document the server no longer has.
        assertEquals(
            DeletionRequestPolicy.Outcome.AlreadyGone,
            DeletionRequestPolicy.decide(exists = false, deletionRequestedAtEpochMillis = originalMarker),
        )
    }
}
