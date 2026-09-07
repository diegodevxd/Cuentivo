package com.cuentivo.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a denied upload is "already done" or "rejected".
 *
 * Getting it wrong in one direction leaves an outbox that never empties; in the other it deletes a
 * queued change that never reached the cloud. So both directions are asserted, not just the happy one.
 */
class SyncRemoteImmutabilityTest {

    private val sale = """{"businessId":"b","totalCents":40000}"""
    private val ownerMember = """{"businessId":"b","userId":"u","role":"OWNER","status":"ACTIVE"}"""
    private val editorMember = """{"businessId":"b","userId":"u","role":"EDITOR","status":"ACTIVE"}"""

    @Test
    fun `every create-only financial document is settled by existing`() {
        listOf("SALE", "SALE_ITEM", "PAYMENT", "PAYMENT_ALLOCATION", "MOVEMENT").forEach {
            assertTrue(it, SyncRemoteImmutability.isSettledByExistence(it, sale))
        }
    }

    @Test
    fun `a document the client may still update is never settled by existing`() {
        // A denial here can be a payload that stopped validating over an older remote copy. Calling
        // that done would throw the correction away, which is worse than a stuck row.
        listOf("CUSTOMER", "PRODUCT", "BUSINESS").forEach {
            assertFalse(it, SyncRemoteImmutability.isSettledByExistence(it, sale))
        }
    }

    @Test
    fun `only the owner membership is settled, because only it can never be updated`() {
        assertTrue(SyncRemoteImmutability.isSettledByExistence("MEMBER", ownerMember))
        // The rules allow updating a non-OWNER member, so a denial on one is a real rejection.
        assertFalse(SyncRemoteImmutability.isSettledByExistence("MEMBER", editorMember))
    }

    @Test
    fun `a member payload that cannot be read is not assumed to be the owner`() {
        assertFalse(SyncRemoteImmutability.isSettledByExistence("MEMBER", "no es json"))
        assertFalse(SyncRemoteImmutability.isSettledByExistence("MEMBER", """{"userId":"u"}"""))
    }

    @Test
    fun `an unknown entity type is not settled`() {
        assertFalse(SyncRemoteImmutability.isSettledByExistence("AUDIT_LOG", sale))
    }

    @Test
    fun `the server is only asked after a permission denial`() {
        // The extra read must never touch the normal path, nor a transient failure: a sale that
        // failed on a flaky network has not been published, and asking would cost a round trip on
        // every retry.
        assertTrue(SyncRemoteImmutability.shouldCheckWhetherAlreadyPublished("SALE", sale, true))
        assertFalse(SyncRemoteImmutability.shouldCheckWhetherAlreadyPublished("SALE", sale, false))
        assertFalse(SyncRemoteImmutability.shouldCheckWhetherAlreadyPublished("CUSTOMER", sale, true))
    }
}
