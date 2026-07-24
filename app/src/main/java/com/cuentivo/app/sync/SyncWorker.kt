package com.cuentivo.app.sync

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cuentivo.app.data.local.CUENTIVO_MIGRATIONS
import com.cuentivo.app.data.local.CuentivoDatabase

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        // WorkManager can run this in a process where MainActivity never started, so the emulator
        // wiring has to happen here too. The call is idempotent within a process.
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

    private suspend fun push(database: CuentivoDatabase, gateway: SyncRemoteGateway): Boolean {
        val dao = database.syncOperationDao()
        val operations = dao.pending(BATCH_SIZE)
        if (operations.isEmpty()) return false

        val outcome = SyncCoordinator(gateway).process(
            operations.map { PendingSyncOperation(it.id, it.businessId, it.entityType, it.entityId, it.payload) }
        )
        outcome.completedIds.forEach { operationId -> dao.remove(operationId) }
        outcome.permanentFailures.forEach { (id, error) -> dao.markFailure(id, "PERMANENT:$error") }
        outcome.retryIds.forEach { id -> dao.markFailure(id, "Retry requested") }
        dao.parkExhausted(MAX_ATTEMPTS)
        return outcome.retryIds.isNotEmpty()
    }

    private suspend fun pull(database: CuentivoDatabase, gateway: SyncRemotePullGateway): Boolean {
        val business = database.businessDao().first() ?: return false
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
    }
}
