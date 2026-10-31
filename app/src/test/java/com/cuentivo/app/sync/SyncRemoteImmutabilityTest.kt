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
    fun `the same cents match whether they arrive as Int or as Long`() {
        // The queued JSON yields Int, Firestore answers Long. Reading that as a conflict would park
        // every single financial row the moment this check started running.
        assertTrue(
            SyncRemoteImmutability.isAlreadyTheSameDocument(
                mapOf("totalCents" to 40000, "notes" to null, "isCanceled" to false),
                mapOf("totalCents" to 40000L, "notes" to null, "isCanceled" to false),
            ),
        )
    }

    @Test
    fun `a different amount under the same id is not the same document`() {
        // These documents can never be rewritten, so calling this settled would bury a divergence
        // where nobody would ever look at it again.
        assertFalse(
            SyncRemoteImmutability.isAlreadyTheSameDocument(
                mapOf("totalCents" to 40000L),
                mapOf("totalCents" to 40001L),
            ),
        )
    }

    @Test
    fun `a key too many or too few is not the same document`() {
        assertFalse(
            SyncRemoteImmutability.isAlreadyTheSameDocument(
                mapOf("totalCents" to 1L),
                mapOf("totalCents" to 1L, "notes" to "algo"),
            ),
        )
        assertFalse(
            SyncRemoteImmutability.isAlreadyTheSameDocument(
                mapOf("totalCents" to 1L, "notes" to "algo"),
                mapOf("totalCents" to 1L),
            ),
        )
    }

    @Test
    fun `an owner membership whose remote role differs is not settled`() {
        // Codex's objection, made into a test: the role in the payload says nothing about what is
        // actually published under that id.
        assertFalse(
            SyncRemoteImmutability.isAlreadyTheSameDocument(
                mapOf("userId" to "u", "role" to "OWNER", "status" to "ACTIVE"),
                mapOf("userId" to "u", "role" to "OWNER", "status" to "REMOVED"),
            ),
        )
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
