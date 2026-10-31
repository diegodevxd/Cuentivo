package com.cuentivo.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoreTest {
    @Test
    fun `same entity and revision always produces the same operation id`() {
        assertEquals(
            SyncOperationId.create("business-1", "CUSTOMER", "customer-1", 100L),
            SyncOperationId.create("business-1", "CUSTOMER", "customer-1", 100L),
        )
    }

    @Test
    fun `newer remote version is applied`() {
        assertEquals(MergeDecision.ApplyRemote, RemoteMergePolicy.decide(localUpdatedAt = 10L, remoteUpdatedAt = 11L, sameContent = false))
    }

    @Test
    fun `equal timestamp with different content is retained as conflict`() {
        assertEquals(MergeDecision.Conflict, RemoteMergePolicy.decide(localUpdatedAt = 10L, remoteUpdatedAt = 10L, sameContent = false))
    }

    @Test
    fun `transient failure is retried but permission failure is not`() {
        assertTrue(SyncFailurePolicy.shouldRetry(SyncFailure.Transient("offline")))
        assertTrue(!SyncFailurePolicy.shouldRetry(SyncFailure.Permanent("permission-denied")))
    }

    @Test
    fun `coordinator removes only successfully uploaded operations`() {
        val gateway = object : SyncRemoteGateway {
            override fun upload(operation: PendingSyncOperation): SyncUploadResult =
                if (operation.id == "ok") SyncUploadResult.Success else SyncUploadResult.Failure(SyncFailure.Transient("offline"))
        }
        val result = SyncCoordinator(gateway).process(
            listOf(
                PendingSyncOperation("ok", "business", "CUSTOMER", "customer", "{}"),
                PendingSyncOperation("retry", "business", "CUSTOMER", "customer", "{}"),
            ),
        )

        assertEquals(listOf("ok"), result.completedIds)
        assertEquals(listOf("retry"), result.retryIds)
    }

    @Test
    fun `remote path is always scoped to the business`() {
        assertEquals(
            "businesses/b-1/customers/c-1",
            FirestoreDocumentPath.forOperation(PendingSyncOperation("id", "b-1", "CUSTOMER", "c-1", "{}")),
        )
    }
}
