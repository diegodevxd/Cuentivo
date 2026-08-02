package com.cuentivo.app.sync

import android.content.Context
import android.util.Log
import com.cuentivo.app.data.local.SyncOperationDao
import com.cuentivo.app.data.local.SyncOperationEntity
import org.json.JSONObject

private const val TAG = "SyncQueue"

class SyncQueue(private val dao: SyncOperationDao, private val context: Context) {
    /**
     * Records the change in the outbox, then asks for a worker to drain it.
     *
     * The two halves are deliberately unequal. The row is durable and must succeed — if it cannot
     * be written the caller has to know, so that failure still propagates. Waking the worker is
     * only a hint: it fails on a device where WorkManager never initialised, and letting that out
     * would abort the caller *after* its own local write had already committed, leaving a saved row
     * that nothing on screen refreshed to show. Nothing is lost either way — the row is queued and
     * drains on the next run.
     */
    suspend fun enqueue(businessId: String, entityType: String, entityId: String, revision: Long, fields: Map<String, Any?>) {
        dao.enqueue(SyncOperationEntity(
            id = SyncOperationId.create(businessId, entityType, entityId, revision),
            businessId = businessId,
            entityType = entityType,
            entityId = entityId,
            operation = "UPSERT",
            payload = JSONObject(fields).toString(),
            createdAtEpochMillis = revision,
            attemptCount = 0,
            lastError = null,
        ))
        SyncScheduling.attempt { SyncScheduler.enqueue(context) }?.let { failure ->
            // Entity type only. Never the payload: it holds names, phones and amounts.
            Log.w(TAG, "No se pudo programar la sincronización de $entityType; queda en la bandeja de salida.", failure)
        }
    }
}
