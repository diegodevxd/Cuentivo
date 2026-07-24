package com.cuentivo.app.sync

import com.cuentivo.app.data.local.BusinessDao
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.BusinessMemberEntity
import com.cuentivo.app.data.local.SyncOperationDao

/**
 * Publishes the owner membership document a business needs before anything else can sync.
 *
 * Every subcollection rule in firestore.rules is gated behind `isMember(businessId)`, which
 * requires an existing `businesses/{businessId}/members/{uid}` document with `status == 'ACTIVE'`.
 * Until that document reaches Firestore, customers, products, sales, payments and movements are
 * all rejected with PERMISSION_DENIED and parked as permanent failures.
 */
class OwnerMembershipBootstrap(
    private val businessDao: BusinessDao,
    private val syncOperationDao: SyncOperationDao,
    private val syncQueue: SyncQueue,
) {
    suspend fun ensure(business: BusinessEntity) {
        val member = BusinessMemberEntity(
            id = memberId(business.id, business.ownerUserId),
            businessId = business.id,
            userId = business.ownerUserId,
            role = OWNER_ROLE,
            status = ACTIVE_STATUS,
            invitedAtEpochMillis = null,
            acceptedAtEpochMillis = business.createdAtEpochMillis,
        )
        val isFirstTime = businessDao.insertMember(member) != IGNORED

        // The BUSINESS operation was enqueued with the business creation timestamp, and the
        // rules check `getAfter(businesses/{businessId}).ownerUserId`, so the business document
        // must land first. Queue ordering is by createdAtEpochMillis, so +1 places this right
        // after the business and ahead of every operational row enqueued later.
        syncQueue.enqueue(
            businessId = business.id,
            entityType = MEMBER_ENTITY_TYPE,
            entityId = business.ownerUserId,
            revision = business.createdAtEpochMillis + 1,
            // validMember() uses hasExactKeys: these six keys, no more and no fewer.
            fields = mapOf(
                "businessId" to business.id,
                "userId" to business.ownerUserId,
                "role" to OWNER_ROLE,
                "status" to ACTIVE_STATUS,
                "invitedAtEpochMillis" to null,
                "acceptedAtEpochMillis" to business.createdAtEpochMillis,
            ),
        )

        if (isFirstTime) {
            // Rows rejected before the membership existed are legitimately retryable now.
            // Without this they stay excluded from `pending` forever.
            syncOperationDao.reopenParkedOperations()
        }
    }

    private fun memberId(businessId: String, userId: String): String = "$businessId:$userId"

    private companion object {
        const val OWNER_ROLE = "OWNER"
        const val ACTIVE_STATUS = "ACTIVE"
        const val MEMBER_ENTITY_TYPE = "MEMBER"
        const val IGNORED = -1L
    }
}
