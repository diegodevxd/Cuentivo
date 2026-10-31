package com.cuentivo.app.sync

import android.util.Log
import com.cuentivo.app.domain.BusinessDeletionPlanner
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source

/**
 * Erases a business from Firestore, root and branches, once the owner has requested it.
 *
 * The only writes here are deletes, and the only thing that makes them legal is the one-way
 * deletion flag set by [FirestoreSyncGateway.requestBusinessDeletion]: `firestore.rules` gates every
 * `allow delete` on `canDeleteForDeletion(businessId)` — the same verified owner, and the flag
 * present. Nothing else can ever delete.
 *
 * The order of the cascade is decided by [BusinessDeletionPlanner], not here: children before
 * parents, batches of at most 500 (Firestore's `WriteBatch` ceiling), and the business document
 * alone in the final batch. This class only lists what the planner should know about and executes
 * its batches, awaiting each one before the next, so a failure stops the cascade at a clean
 * boundary and a retry re-lists only what is left.
 *
 * Every read here is `Source.SERVER` on purpose: this flow must see what actually lives in the
 * cloud, not what a stale local cache remembers. A missing business document means the erasure is
 * already done — treat it as success, or a retry after a partial failure would be stuck forever.
 */
class FirestoreDeletionGateway(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    /**
     * Deletes every known document under [businessId], children first, then the business itself.
     *
     * @return success when the cloud copy is gone — including when it was already gone; failure when
     *   any batch was rejected or the network dropped. The caller must treat failure as "nothing
     *   local is touched": local data, session, entitlement and the Auth account all survive a
     *   failed remote erasure so the person can retry.
     */
    fun deleteBusiness(businessId: String): Result<Unit> {
        return try {
            if (!Tasks.await(businessDocument(businessId).get(Source.SERVER)).exists()) {
                // Already erased (e.g. a retry after the business batch committed but the phone died
                // before wiping itself). Nothing left to do.
                return Result.success(Unit)
            }
            val inventory = fetchInventory(businessId)
            BusinessDeletionPlanner.plan(inventory).forEach { batch ->
                val writeBatch = firestore.batch()
                batch.documentPaths.forEach { path -> writeBatch.delete(firestore.document(path)) }
                Tasks.await(writeBatch.commit())
            }
            Result.success(Unit)
        } catch (error: Exception) {
            // Never rethrown into the delete flow: a partial remote erasure must not take the phone
            // with it. The caller keeps local state intact and tells the person the cloud copy is
            // still pending. No ids, no PII — just the signal.
            Log.w(TAG, "Business erasure failed", error)
            Result.failure(error)
        }
    }

    /** Lists every document id the cascade needs to know about, reading from the server. */
    private fun fetchInventory(businessId: String): BusinessDeletionPlanner.Inventory {
        val root = firestore.collection("businesses").document(businessId)
        fun ids(collection: String): List<String> =
            serverIds(Tasks.await(root.collection(collection).get(Source.SERVER)))
        fun childIds(parent: DocumentReference, collection: String): List<String> =
            serverIds(Tasks.await(parent.collection(collection).get(Source.SERVER)))

        val saleIds = ids("sales")
        val paymentIds = ids("payments")
        return BusinessDeletionPlanner.Inventory(
            businessId = businessId,
            memberIds = ids("members"),
            customerIds = ids("customers"),
            productIds = ids("products"),
            saleIds = saleIds,
            saleItemIds = saleIds.associateWith { saleId ->
                childIds(root.collection("sales").document(saleId), "items")
            },
            paymentIds = paymentIds,
            allocationIds = paymentIds.associateWith { paymentId ->
                childIds(root.collection("payments").document(paymentId), "allocations")
            },
            movementIds = ids("movements"),
            auditLogIds = ids("auditLogs"),
        )
    }

    private fun businessDocument(businessId: String): DocumentReference =
        firestore.collection("businesses").document(businessId)

    private fun serverIds(snapshot: QuerySnapshot): List<String> = snapshot.documents.map { it.id }

    private companion object {
        const val TAG = "CuentivoDeletion"
    }
}
