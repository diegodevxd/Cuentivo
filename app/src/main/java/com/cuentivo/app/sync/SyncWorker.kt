package com.cuentivo.app.sync

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cuentivo.app.billing.EntitlementStore
import com.cuentivo.app.data.local.CUENTIVO_MIGRATIONS
import com.cuentivo.app.data.local.CuentivoDatabase
import com.google.firebase.auth.FirebaseAuth

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        // Second gate, and the one that actually matters. SyncScheduler refuses to schedule for a
        // free account, but work queued while Pro was active outlives the subscription, and
        // WorkManager would happily run it days later. So it is asked again here, in the process
        // that is about to touch the network, immediately before it does.
        //
        // Nothing is deleted and nothing is marked failed: the outbox rows stay exactly as they are,
        // so resubscribing drains them. Returning success rather than retry is deliberate — this is
        // not a transient failure, and retrying forever would burn battery on someone who has simply
        // chosen the free plan.
        if (!EntitlementStore(applicationContext).allowsCloudSync()) return Result.success()

        // WorkManager can run this in a process where MainActivity never started, so the App Check
        // and emulator wiring has to happen here too. Both calls are idempotent within a process.
        AppCheckInstaller.installOnce()
        FirebaseEmulators.connectIfEnabled()
        val database = Room.databaseBuilder(applicationContext, CuentivoDatabase::class.java, "cuentivo.db")
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()
        return try {
            val gateway = FirestoreSyncGateway()
            // Push first: a device that just wrote locally should publish before it adopts, so the
            // merge sees its own rows as already present instead of racing them.
            val pushNeedsRetry = push(database, gateway)
            val pullNeedsRetry = pull(database, gateway)
            if (pushNeedsRetry || pullNeedsRetry) Result.retry() else Result.success()
        } catch (error: Exception) {
            // Anything escaping here used to mark the job FAILED, which WorkManager never
            // reschedules, leaving the queue permanently undrained. Retry is the safe failure mode.
            Result.retry()
        } finally {
            database.close()
        }
    }

    /**
     * Drains the outbox rather than uploading a single page of it.
     *
     * This used to read one batch of 50 and return success, so a queue of 60 quietly left ten rows
     * behind until something unrelated scheduled another run. The loop itself lives in [SyncDrainer]
     * so it can be tested with fakes; this method only wires it to Room and the coordinator.
     */
    private suspend fun push(database: CuentivoDatabase, gateway: SyncRemoteGateway): Boolean {
        val dao = database.syncOperationDao()
        // Before draining, give a second look to the rows parked on PERMISSION_DENIED that name a
        // document nobody may rewrite. Under the old behaviour those were the outbox failing to tell
        // "already published" from "rejected", and they were shut out of pending() for good. The cap
        // is the same one parkExhausted enforces, so a row that really is denied parks itself again.
        dao.reopenSettledPermissionDenials(MAX_ATTEMPTS)
        val coordinator = SyncCoordinator(gateway)
        return SyncDrainer(
            batchSize = BATCH_SIZE,
            maxBatches = MAX_BATCHES,
            pending = { limit ->
                dao.pending(limit).map {
                    PendingSyncOperation(it.id, it.businessId, it.entityType, it.entityId, it.payload)
                }
            },
            process = { coordinator.process(it) },
            onCompleted = { dao.remove(it) },
            onPermanentFailure = { id, error -> dao.markFailure(id, "PERMANENT:$error") },
            onTransientFailure = { dao.markFailure(it, "Retry requested") },
            parkExhausted = { dao.parkExhausted(MAX_ATTEMPTS) },
        ).drain()
    }

    private suspend fun pull(database: CuentivoDatabase, gateway: SyncRemotePullGateway): Boolean {
        // Scoped to the signed-in owner, like everywhere else in the app. "The first business in the
        // database" is the shape of bug that stranded a reinstalled phone's data in the first place.
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        val business = userId?.let { database.businessDao().firstOwnedBy(it) } ?: return false
        val since = database.syncStateDao().lastPulledAt(business.id) ?: 0L
        return when (val result = gateway.download(business.id, since)) {
            is SyncDownloadResult.Success -> {
                RemoteMerger(database).apply(business.id, result.documents)
                false
            }

            is SyncDownloadResult.Failure -> SyncFailurePolicy.shouldRetry(result.failure)
        }
    }

    private companion object {
        const val BATCH_SIZE = 50
        const val MAX_ATTEMPTS = 10

        /**
         * Safety cap, not the termination guarantee — that comes from the pending set shrinking on
         * every pass. At 50 a batch this is 10,000 operations in one run, far more than a phone
         * accumulates offline, and anything beyond it simply continues on the next run.
         */
        const val MAX_BATCHES = 200
    }
}
