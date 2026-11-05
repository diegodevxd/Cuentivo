package com.cuentivo.app.domain

/**
 * Decides the order and batching of a business erasure — nothing more.
 *
 * This is the pure half of the deletion flow, mirroring the planner pattern of
 * [LegacyMigrationPlanner]: it decides but never touches Firestore. The gateway that executes it
 * (`sync/FirestoreDeletionGateway`) feeds it the inventory of document ids it listed remotely and
 * turns each returned batch into one `WriteBatch`, so the two security-relevant properties of the
 * cascade — child-first order and the 500-writes-per-batch ceiling — live here where a JVM test can
 * pin them, and nowhere where they could drift.
 *
 * Ordering is load-bearing, not style. Firestore does not cascade: deleting a sale does not delete
 * its items subcollection, and deleting the business document does not delete anything else. A
 * failed batch must leave parents in place so a retry finds their children again, so children are
 * always listed before the parent that owns them, and the business document — the last thing that
 * could possibly be touched — is always alone in the final batch.
 */
object BusinessDeletionPlanner {

    /** Firestore rejects a `WriteBatch` with more than 500 operations. This is the ceiling, not a target. */
    const val MAX_WRITES_PER_BATCH = 500

    /** Everything the client knows to exist under the business, keyed the same way the rules see it. */
    data class Inventory(
        val businessId: String,
        val memberIds: List<String> = emptyList(),
        val customerIds: List<String> = emptyList(),
        val productIds: List<String> = emptyList(),
        /** saleId -> its item ids; each item lives at `sales/{saleId}/items/{itemId}`. */
        val saleItemIds: Map<String, List<String>> = emptyMap(),
        val saleIds: List<String> = emptyList(),
        /** paymentId -> its allocation ids; each allocation lives at `payments/{paymentId}/allocations/{allocationId}`. */
        val allocationIds: Map<String, List<String>> = emptyMap(),
        val paymentIds: List<String> = emptyList(),
        val movementIds: List<String> = emptyList(),
        val auditLogIds: List<String> = emptyList(),
    )

    /** One `WriteBatch` worth of deletes: full document paths, never more than [MAX_WRITES_PER_BATCH]. */
    data class Batch(val documentPaths: List<String>)

    /**
     * Returns the batches in execution order. Guarantees:
     * - children before parents (items before their sale, allocations before their payment),
     * - no batch exceeds [MAX_WRITES_PER_BATCH],
     * - the business document is the last path, alone in the final batch,
     * - an empty inventory still yields the single batch that erases the business document.
     */
    fun plan(inventory: Inventory): List<Batch> {
        val childrenAndCollections = buildList {
            inventory.saleItemIds.forEach { (saleId, itemIds) ->
                itemIds.forEach { itemId -> add(path(inventory.businessId, "sales/$saleId/items/$itemId")) }
            }
            inventory.saleIds.forEach { saleId -> add(path(inventory.businessId, "sales/$saleId")) }
            inventory.allocationIds.forEach { (paymentId, allocationIds) ->
                allocationIds.forEach { allocationId -> add(path(inventory.businessId, "payments/$paymentId/allocations/$allocationId")) }
            }
            inventory.paymentIds.forEach { paymentId -> add(path(inventory.businessId, "payments/$paymentId")) }
            inventory.memberIds.forEach { memberId -> add(path(inventory.businessId, "members/$memberId")) }
            inventory.customerIds.forEach { customerId -> add(path(inventory.businessId, "customers/$customerId")) }
            inventory.productIds.forEach { productId -> add(path(inventory.businessId, "products/$productId")) }
            inventory.movementIds.forEach { movementId -> add(path(inventory.businessId, "movements/$movementId")) }
            inventory.auditLogIds.forEach { auditId -> add(path(inventory.businessId, "auditLogs/$auditId")) }
        }
        val subcollectionBatches = childrenAndCollections.chunked(MAX_WRITES_PER_BATCH).map { Batch(it) }
        // The business document goes last and alone, whatever the size of everything before it.
        return subcollectionBatches + Batch(listOf("businesses/${inventory.businessId}"))
    }

    private fun path(businessId: String, suffix: String): String = "businesses/$businessId/$suffix"
}
