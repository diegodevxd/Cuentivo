package com.cuentivo.app.sync

import android.util.Log
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.domain.OutboxBackfillPlanner
import com.cuentivo.app.domain.SyncEntityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OutboxBackfill"

/**
 * Queues everything a business already holds locally, for an account that has just become Pro.
 *
 * The free plan writes nothing to the cloud — [SyncQueue] returns early — which is what stops an
 * abandoned reinstall's business from surfacing in Firestore months later. The price is that
 * upgrading arrives at a full database and an empty outbox, and this pays it in one pass.
 *
 * Safe to run more than once, and it will be: it runs on every confirmation of Pro, not only on the
 * first. `SyncOperationId.create` hashes `businessId|entityType|entityId|revision` and the insert
 * ignores conflicts, so a row already queued or already sent is not queued twice.
 *
 * The payloads come from [SyncPayloads], the same builders the ordinary write paths in
 * `MainActivity` use. They used to be a second hand-written copy of the same maps, which meant
 * adding a column to an entity had to be remembered in two places; forgetting one published a
 * document that validates when it is written normally and is rejected when it arrives through the
 * backfill — silently, because a rejection parks permanent. The exhaustiveness test in
 * `OutboxBackfillPlannerTest` catches a missing *type*, never a missing *field*.
 */
class OutboxBackfill(
    private val database: CuentivoDatabase,
    private val syncQueue: SyncQueue,
) {

    /**
     * @param businessId the business to publish.
     * @return how many rows were handed to the queue, for logging. Rows already queued are counted
     *   too; the queue's own conflict handling is what makes repeating harmless.
     */
    suspend fun seed(businessId: String): Int = withContext(Dispatchers.IO) {
        val business = database.businessDao().byId(businessId) ?: run {
            Log.w(TAG, "Backfill skipped: the business is not on this device.")
            return@withContext 0
        }
        // Rows parked by an earlier attempt come back into play first.
        //
        // A rejected write is marked PERMANENT and disappears from `pending` for good, which is
        // right while the cause stands and wrong the moment it is fixed. Everything queued behind a
        // missing membership was rejected for a reason that no longer holds, and re-enqueueing does
        // not revive it: the outbox id is a hash of the same four fields, so `INSERT IGNORE` sees
        // the parked row and keeps it parked. Without this the fix would work only for accounts
        // that had never tried and failed.
        database.syncOperationDao().reopenParkedOperations()

        val members = database.businessDao().membersForBackfill(businessId)
        val customers = database.customerDao().allForBackfill(businessId)
        val products = database.productDao().allForBackfill(businessId)
        val financial = database.financialDao()
        val sales = financial.allSalesForBackfill(businessId)
        val saleItems = financial.allSaleItemsForBackfill(businessId)
        val payments = financial.allPaymentsForBackfill(businessId)
        val allocations = financial.allAllocationsForBackfill(businessId)
        val movements = financial.allMovementsForBackfill(businessId)

        val counts = mapOf(
            SyncEntityType.BUSINESS to 1,
            SyncEntityType.MEMBER to members.size,
            SyncEntityType.CUSTOMER to customers.size,
            SyncEntityType.PRODUCT to products.size,
            SyncEntityType.SALE to sales.size,
            SyncEntityType.SALE_ITEM to saleItems.size,
            SyncEntityType.PAYMENT to payments.size,
            SyncEntityType.PAYMENT_ALLOCATION to allocations.size,
            SyncEntityType.MOVEMENT to movements.size,
        )

        var queued = 0
        // The planner owns the order, and it is load-bearing: the rules gate every collection
        // behind the member document, so a customer queued before the business and the membership
        // is denied — and a denial is parked permanently rather than retried.
        for (type in OutboxBackfillPlanner.plan(counts)) {
            queued += when (type) {
                SyncEntityType.BUSINESS -> {
                    enqueue(businessId, type, business.id, business.updatedAtEpochMillis, SyncPayloads.business(business))
                    1
                }

                SyncEntityType.MEMBER -> members.onEach {
                    // **The remote document id is the uid, not the Room row id.** The local row is
                    // keyed "$businessId:$userId" while `match /members/{memberUserId}` requires
                    // `request.resource.data.userId == memberUserId`, so publishing under the
                    // composite id is rejected. Getting this wrong cost a whole upload: the
                    // rejection is classified permanent, membership never lands, and every later
                    // write fails `isMember()` — customers, sales and movements all parked behind
                    // one wrong document name, with only the business making it to the cloud.
                    //
                    // +1 on the business timestamp, matching OwnerMembershipBootstrap: the queue
                    // drains by createdAtEpochMillis and the rules read the business document, so
                    // membership has to land immediately after it and before everything else.
                    enqueue(businessId, type, it.userId, business.createdAtEpochMillis + 1, SyncPayloads.member(it))
                }.size

                SyncEntityType.CUSTOMER -> customers.onEach {
                    enqueue(businessId, type, it.id, it.updatedAtEpochMillis, SyncPayloads.customer(it))
                }.size

                SyncEntityType.PRODUCT -> products.onEach {
                    enqueue(businessId, type, it.id, it.updatedAtEpochMillis, SyncPayloads.product(it))
                }.size

                SyncEntityType.SALE -> sales.onEach {
                    enqueue(businessId, type, it.id, it.updatedAtEpochMillis, SyncPayloads.sale(it))
                }.size

                SyncEntityType.SALE_ITEM -> saleItems.onEach {
                    enqueue(businessId, type, it.id, business.updatedAtEpochMillis, SyncPayloads.saleItem(it))
                }.size

                SyncEntityType.PAYMENT -> payments.onEach {
                    enqueue(businessId, type, it.id, it.createdAtEpochMillis, SyncPayloads.payment(it))
                }.size

                SyncEntityType.PAYMENT_ALLOCATION -> allocations.onEach {
                    enqueue(businessId, type, it.id, business.updatedAtEpochMillis, SyncPayloads.allocation(it))
                }.size

                SyncEntityType.MOVEMENT -> movements.onEach {
                    enqueue(businessId, type, it.id, it.occurredAtEpochMillis, SyncPayloads.movement(it))
                }.size
            }
        }
        // Counts only, never a name or an amount: this reaches logcat.
        Log.i(TAG, "Backfill queued $queued rows across ${OutboxBackfillPlanner.plan(counts).size} types.")
        queued
    }

    private suspend fun enqueue(
        businessId: String,
        type: SyncEntityType,
        entityId: String,
        revision: Long,
        fields: Map<String, Any?>,
    ) = syncQueue.enqueue(businessId, type.wireName, entityId, revision, fields)
}
