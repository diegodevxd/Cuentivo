package com.cuentivo.app.sync

import android.content.Context
import com.cuentivo.app.data.local.SyncOperationDao
import com.cuentivo.app.data.local.SyncOperationEntity
import org.json.JSONObject

class SyncQueue(private val dao: SyncOperationDao, private val context: Context) {
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
        SyncScheduler.enqueue(context)
    }
}
