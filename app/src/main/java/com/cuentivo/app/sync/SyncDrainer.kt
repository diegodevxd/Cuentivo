package com.cuentivo.app.sync

/**
 * Empties the outbox, however many batches that takes.
 *
 * The bug this exists for: the worker used to read one page of 50, upload it, and return success.
 * With 60 rows queued, ten of them sat there until some unrelated write happened to schedule another
 * run — which flatly contradicts the promise that regaining Pro drains whatever piled up.
 *
 * Written against lambdas rather than the DAO and the coordinator so the loop can be exercised with
 * fakes in a plain JVM test: 51 successful rows, a permanent failure sitting between valid ones, a
 * transient failure. Those are the cases that matter and none of them need a device.
 *
 * **Termination.** Each pass either deletes rows, marks them permanent (which drops them out of
 * [pending]), or returns. So the pending set strictly shrinks and the loop cannot spin. [maxBatches]
 * is a belt-and-braces cap on top of that, not the actual guarantee.
 */
class SyncDrainer(
    private val batchSize: Int,
    private val maxBatches: Int,
    private val pending: suspend (Int) -> List<PendingSyncOperation>,
    private val process: suspend (List<PendingSyncOperation>) -> SyncProcessResult,
    private val onCompleted: suspend (String) -> Unit,
    private val onPermanentFailure: suspend (String, String) -> Unit,
    private val onTransientFailure: suspend (String) -> Unit,
    private val parkExhausted: suspend () -> Unit,
) {
    /** @return true when the caller should ask WorkManager to run again. */
    suspend fun drain(): Boolean {
        repeat(maxBatches) {
            val batch = pending(batchSize)
            if (batch.isEmpty()) return false

            val outcome = process(batch)

            // Removal happens only after the upload is confirmed complete, never before.
            outcome.completedIds.forEach { onCompleted(it) }
            // Permanent failures are parked, not deleted. Parking takes them out of `pending`, which
            // is what stops one poisoned row from blocking every valid row queued behind it.
            outcome.permanentFailures.forEach { (id, error) -> onPermanentFailure(id, error) }
            outcome.retryIds.forEach { onTransientFailure(it) }
            parkExhausted()

            // A transient failure means the network or the backend is unhappy. Grinding through the
            // remaining batches would just pile up more failures, so hand back to WorkManager, which
            // knows how to wait. The rows stay queued.
            if (outcome.retryIds.isNotEmpty()) return true

            // Nothing moved and nothing failed: an outcome that touches no row. Continuing would
            // re-read the same page forever.
            if (outcome.completedIds.isEmpty() && outcome.permanentFailures.isEmpty()) return false
        }
        // Hit the cap with rows still queued. Not an error — come back and carry on.
        return true
    }
}
