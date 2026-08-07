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
 * The payloads below mirror the ones the ordinary write paths in `MainActivity` build. That
 * duplication is deliberate and the alternative is worse: the write paths compose their maps from
 * values in hand mid-transaction, while this one has only the stored entity. Sharing a builder
 * would mean either passing entities through the hot path or reconstructing them here anyway.
 * **If a column is added to an entity, it has to be added in both places** — the exhaustiveness
 * test in `OutboxBackfillPlannerTest` catches a missing *type*, not a missing *field*.
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
                    enqueue(businessId, type, business.id, business.updatedAtEpochMillis, mapOf(
                        "name" to business.name,
                        "ownerUserId" to business.ownerUserId,
                        "currency" to business.currency,
                        "timezone" to business.timezone,
                        "createdAtEpochMillis" to business.createdAtEpochMillis,
                        "updatedAtEpochMillis" to business.updatedAtEpochMillis,
                    ))
                    1
                }

                SyncEntityType.MEMBER -> members.onEach {
                    // +1 on the business timestamp, matching OwnerMembershipBootstrap: the queue
                    // drains by createdAtEpochMillis and the rules read the business document, so
                    // membership has to land immediately after it and before everything else.
                    enqueue(businessId, type, it.id, business.createdAtEpochMillis + 1, mapOf(
                        "businessId" to it.businessId,
                        "userId" to it.userId,
                        "role" to it.role,
                        "status" to it.status,
                        "invitedAtEpochMillis" to it.invitedAtEpochMillis,
                        "acceptedAtEpochMillis" to it.acceptedAtEpochMillis,
                    ))
                }.size

                SyncEntityType.CUSTOMER -> customers.onEach {
                    enqueue(businessId, type, it.id, it.updatedAtEpochMillis, mapOf(
                        "businessId" to it.businessId,
                        "name" to it.name,
                        "normalizedName" to it.normalizedName,
                        "phone" to it.phone,
                        "email" to it.email,
                        "address" to it.address,
                        "notes" to it.notes,
                        "creditLimitCents" to it.creditLimitCents,
                        "isActive" to it.isActive,
                        "createdAtEpochMillis" to it.createdAtEpochMillis,
                        "updatedAtEpochMillis" to it.updatedAtEpochMillis,
                        "deletedAtEpochMillis" to it.deletedAtEpochMillis,
                    ))
                }.size

                SyncEntityType.PRODUCT -> products.onEach {
                    enqueue(businessId, type, it.id, it.updatedAtEpochMillis, mapOf(
                        "businessId" to it.businessId,
                        "name" to it.name,
                        "description" to it.description,
                        "sku" to it.sku,
                        "category" to it.category,
                        "salePriceCents" to it.salePriceCents,
                        "costPriceCents" to it.costPriceCents,
                        "stock" to it.stock,
                        "imageUri" to it.imageUri,
                        "isActive" to it.isActive,
                        "createdAtEpochMillis" to it.createdAtEpochMillis,
                        "updatedAtEpochMillis" to it.updatedAtEpochMillis,
                        "deletedAtEpochMillis" to it.deletedAtEpochMillis,
                    ))
                }.size

                SyncEntityType.SALE -> sales.onEach {
                    enqueue(businessId, type, it.id, it.updatedAtEpochMillis, mapOf(
                        "businessId" to it.businessId,
                        "customerId" to it.customerId,
                        "sellerUserId" to it.sellerUserId,
                        "saleNumber" to it.saleNumber,
                        "saleDateEpochDay" to it.saleDateEpochDay,
                        "dueDateEpochDay" to it.dueDateEpochDay,
                        "subtotalCents" to it.subtotalCents,
                        "discountCents" to it.discountCents,
                        "totalCents" to it.totalCents,
                        "isCanceled" to it.isCanceled,
                        "notes" to it.notes,
                        "createdAtEpochMillis" to it.createdAtEpochMillis,
                        "updatedAtEpochMillis" to it.updatedAtEpochMillis,
                    ))
                }.size

                SyncEntityType.SALE_ITEM -> saleItems.onEach {
                    enqueue(businessId, type, it.id, business.updatedAtEpochMillis, mapOf(
                        "saleId" to it.saleId,
                        "productId" to it.productId,
                        "productNameSnapshot" to it.productNameSnapshot,
                        "quantity" to it.quantity,
                        "unitPriceCents" to it.unitPriceCents,
                        "discountCents" to it.discountCents,
                        "subtotalCents" to it.subtotalCents,
                    ))
                }.size

                SyncEntityType.PAYMENT -> payments.onEach {
                    enqueue(businessId, type, it.id, it.createdAtEpochMillis, mapOf(
                        "businessId" to it.businessId,
                        "customerId" to it.customerId,
                        "amountCents" to it.amountCents,
                        "paymentMethod" to it.paymentMethod,
                        "paymentDateEpochDay" to it.paymentDateEpochDay,
                        "reference" to it.reference,
                        "notes" to it.notes,
                        "createdByUserId" to it.createdByUserId,
                        "createdAtEpochMillis" to it.createdAtEpochMillis,
                        "canceledAtEpochMillis" to it.canceledAtEpochMillis,
                    ))
                }.size

                SyncEntityType.PAYMENT_ALLOCATION -> allocations.onEach {
                    enqueue(businessId, type, it.id, business.updatedAtEpochMillis, mapOf(
                        "paymentId" to it.paymentId,
                        "saleId" to it.saleId,
                        "amountCents" to it.amountCents,
                    ))
                }.size

                SyncEntityType.MOVEMENT -> movements.onEach {
                    enqueue(businessId, type, it.id, it.occurredAtEpochMillis, mapOf(
                        "businessId" to it.businessId,
                        "customerId" to it.customerId,
                        "saleId" to it.saleId,
                        "paymentId" to it.paymentId,
                        "type" to it.type,
                        "amountCents" to it.amountCents,
                        "direction" to it.direction,
                        "occurredAtEpochMillis" to it.occurredAtEpochMillis,
                        "createdByUserId" to it.createdByUserId,
                        "reversesMovementId" to it.reversesMovementId,
                        "notes" to it.notes,
                    ))
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
