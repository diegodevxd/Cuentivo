package com.cuentivo.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMergePlannerTest {

    private fun plan(
        entityType: String,
        remoteUpdatedAt: Long = 100L,
        remoteCanceled: Boolean = false,
        local: LocalRecord? = null,
        sameContent: Boolean = false,
    ) = RemoteMergePlanner.plan(entityType, remoteUpdatedAt, remoteCanceled, local, sameContent)

    @Test
    fun `anything missing locally is adopted`() {
        assertEquals(RemoteMergeAction.Adopt, plan("CUSTOMER"))
        assertEquals(RemoteMergeAction.Adopt, plan("MOVEMENT"))
        assertEquals(RemoteMergeAction.Adopt, plan("SALE"))
    }

    @Test
    fun `newer remote copy of a descriptive record wins`() {
        assertEquals(
            RemoteMergeAction.Overwrite,
            plan("CUSTOMER", remoteUpdatedAt = 200L, local = LocalRecord(updatedAtEpochMillis = 100L)),
        )
    }

    @Test
    fun `older remote copy of a descriptive record loses`() {
        assertTrue(
            plan("PRODUCT", remoteUpdatedAt = 50L, local = LocalRecord(updatedAtEpochMillis = 100L))
                is RemoteMergeAction.Skip,
        )
    }

    @Test
    fun `identical content is never rewritten`() {
        assertTrue(
            plan("CUSTOMER", remoteUpdatedAt = 200L, local = LocalRecord(100L), sameContent = true)
                is RemoteMergeAction.Skip,
        )
    }

    @Test
    fun `same timestamp with different content keeps the local copy`() {
        val action = plan("CUSTOMER", remoteUpdatedAt = 100L, local = LocalRecord(100L))
        assertTrue(action is RemoteMergeAction.Skip)
    }

    @Test
    fun `an existing ledger movement is never overwritten even by a newer remote copy`() {
        // The ledger is append-only. A remote device correcting a movement must send a
        // compensating movement, not a rewrite of this row.
        assertTrue(
            plan("MOVEMENT", remoteUpdatedAt = 9_999L, local = LocalRecord(updatedAtEpochMillis = 1L))
                is RemoteMergeAction.Skip,
        )
    }

    @Test
    fun `an existing sale is never overwritten even by a newer remote copy`() {
        assertTrue(
            plan("SALE", remoteUpdatedAt = 9_999L, local = LocalRecord(updatedAtEpochMillis = 1L))
                is RemoteMergeAction.Skip,
        )
    }

    @Test
    fun `a remote cancellation this device has not seen is applied`() {
        assertEquals(
            RemoteMergeAction.ApplyCancellation,
            plan("SALE", remoteCanceled = true, local = LocalRecord(updatedAtEpochMillis = 1L, isCanceled = false)),
        )
        assertEquals(
            RemoteMergeAction.ApplyCancellation,
            plan("PAYMENT", remoteCanceled = true, local = LocalRecord(updatedAtEpochMillis = 1L, isCanceled = false)),
        )
    }

    @Test
    fun `a cancellation already recorded locally is not reapplied`() {
        assertTrue(
            plan("SALE", remoteCanceled = true, local = LocalRecord(updatedAtEpochMillis = 1L, isCanceled = true))
                is RemoteMergeAction.Skip,
        )
    }

    @Test
    fun `a cancellation is never undone by a remote copy that is still active`() {
        assertTrue(
            plan("SALE", remoteCanceled = false, local = LocalRecord(updatedAtEpochMillis = 1L, isCanceled = true))
                is RemoteMergeAction.Skip,
        )
    }

    @Test
    fun `an unknown entity type is skipped rather than guessed at`() {
        val action = plan("SOMETHING_ELSE")
        assertTrue(action is RemoteMergeAction.Skip)
        assertTrue((action as RemoteMergeAction.Skip).reason.contains("unsupported"))
    }
}
