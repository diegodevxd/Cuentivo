package com.cuentivo.app.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 51st operation.
 *
 * The worker used to read one page of 50, upload it and return success. With more rows queued the
 * remainder sat there until something unrelated scheduled another run — which contradicts the
 * promise that regaining Pro drains whatever piled up while it was gone.
 *
 * A fake queue stands in for Room and the coordinator, so these run in milliseconds and cover the
 * cases that matter: many batches, a poisoned row among good ones, and a network wobble.
 */
class SyncDrainerTest {

    /** Mimics `sync_operations`: rows leave by being removed, or by being parked as permanent. */
    private class FakeQueue(count: Int) {
        val rows = (1..count)
            .map { PendingSyncOperation("op-$it", "business-1", "CUSTOMER", "entity-$it", "{}") }
            .toMutableList()
        val parked = mutableMapOf<String, String>()
        val removed = mutableListOf<String>()
        val retried = mutableListOf<String>()
        var parkExhaustedCalls = 0

        /** Parked rows are invisible here, exactly as the DAO's `PERMANENT:` filter makes them. */
        fun pending(limit: Int) = rows.filterNot { parked.containsKey(it.id) }.take(limit)

        fun remove(id: String) {
            removed += id
            rows.removeAll { it.id == id }
        }
    }

    private fun drainer(
        queue: FakeQueue,
        batchSize: Int = 50,
        maxBatches: Int = 200,
        process: (List<PendingSyncOperation>) -> SyncProcessResult,
    ) = SyncDrainer(
        batchSize = batchSize,
        maxBatches = maxBatches,
        pending = { queue.pending(it) },
        process = { process(it) },
        onCompleted = { queue.remove(it) },
        onPermanentFailure = { id, error -> queue.parked[id] = error },
        onTransientFailure = { queue.retried += it },
        parkExhausted = { queue.parkExhaustedCalls++ },
    )

    private fun allSucceed(batch: List<PendingSyncOperation>) =
        SyncProcessResult(batch.map { it.id }, emptyList(), emptyMap())

    @Test
    fun `fifty one successful operations all get uploaded, not just the first fifty`() {
        val queue = FakeQueue(51)

        val needsRetry = runBlocking { drainer(queue) { allSucceed(it) }.drain() }

        assertEquals(51, queue.removed.size)
        assertTrue(queue.rows.isEmpty())
        // Nothing left behind, so there is nothing to come back for.
        assertFalse(needsRetry)
    }

    @Test
    fun `a queue several batches deep drains completely in one run`() {
        val queue = FakeQueue(230)

        runBlocking { drainer(queue) { allSucceed(it) }.drain() }

        assertEquals(230, queue.removed.size)
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun `an empty queue does nothing and asks for nothing`() {
        val queue = FakeQueue(0)

        val needsRetry = runBlocking { drainer(queue) { allSucceed(it) }.drain() }

        assertTrue(queue.removed.isEmpty())
        assertFalse(needsRetry)
    }

    @Test
    fun `a permanently rejected row is parked and the valid rows behind it still go up`() {
        // The poisoned row sits in the middle of the first batch. Parking takes it out of `pending`,
        // which is what stops it blocking everything queued after it.
        val queue = FakeQueue(60)
        val poisoned = "op-25"

        val needsRetry = runBlocking {
            drainer(queue) { batch ->
                SyncProcessResult(
                    completedIds = batch.map { it.id }.filterNot { it == poisoned },
                    retryIds = emptyList(),
                    permanentFailures = batch.filter { it.id == poisoned }
                        .associate { it.id to "INVALID_ARGUMENT" },
                )
            }.drain()
        }

        assertEquals(59, queue.removed.size)
        assertTrue(queue.removed.containsAll(listOf("op-1", "op-26", "op-60")))
        // Parked, never deleted: the payload stays in the table for inspection.
        assertEquals("INVALID_ARGUMENT", queue.parked[poisoned])
        assertFalse(queue.removed.contains(poisoned))
        assertFalse(needsRetry)
    }

    @Test
    fun `a transient failure keeps the row and asks to come back`() {
        val queue = FakeQueue(60)

        val needsRetry = runBlocking {
            drainer(queue) { batch ->
                SyncProcessResult(emptyList(), batch.map { it.id }, emptyMap())
            }.drain()
        }

        assertTrue(needsRetry)
        // Nothing was deleted — a network wobble must never cost an operation.
        assertTrue(queue.removed.isEmpty())
        assertEquals(50, queue.retried.size)
    }

    @Test
    fun `a transient failure stops the run instead of grinding through every batch`() {
        // Continuing after the network says no would just pile up failures. WorkManager knows how
        // to wait; the drainer does not need to.
        val queue = FakeQueue(500)
        var batches = 0

        runBlocking {
            drainer(queue) { batch ->
                batches++
                SyncProcessResult(emptyList(), batch.map { it.id }, emptyMap())
            }.drain()
        }

        assertEquals(1, batches)
    }

    @Test
    fun `an outcome that moves nothing does not spin forever`() {
        val queue = FakeQueue(10)
        var batches = 0

        val needsRetry = runBlocking {
            drainer(queue) {
                batches++
                SyncProcessResult(emptyList(), emptyList(), emptyMap())
            }.drain()
        }

        assertEquals(1, batches)
        assertFalse(needsRetry)
    }

    @Test
    fun `hitting the batch cap asks to come back rather than declaring success`() {
        // The safety net: a queue larger than one run allows must not be reported as drained.
        val queue = FakeQueue(60)

        val needsRetry = runBlocking { drainer(queue, maxBatches = 1) { allSucceed(it) }.drain() }

        assertEquals(50, queue.removed.size)
        assertTrue(queue.rows.isNotEmpty())
        assertTrue(needsRetry)
    }
}
