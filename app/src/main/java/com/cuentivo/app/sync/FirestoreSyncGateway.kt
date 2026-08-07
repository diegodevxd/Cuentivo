package com.cuentivo.app.sync

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import org.json.JSONObject

object FirestoreDocumentPath {
    fun forOperation(operation: PendingSyncOperation): String = when (operation.entityType) {
        "BUSINESS" -> "businesses/${operation.entityId}"
        "MEMBER" -> "businesses/${operation.businessId}/members/${operation.entityId}"
        "CUSTOMER" -> "businesses/${operation.businessId}/customers/${operation.entityId}"
        "PRODUCT" -> "businesses/${operation.businessId}/products/${operation.entityId}"
        "SALE" -> "businesses/${operation.businessId}/sales/${operation.entityId}"
        "SALE_ITEM" -> "businesses/${operation.businessId}/sales/${JSONObject(operation.payload).getString("saleId")}/items/${operation.entityId}"
        "PAYMENT" -> "businesses/${operation.businessId}/payments/${operation.entityId}"
        "PAYMENT_ALLOCATION" -> "businesses/${operation.businessId}/payments/${JSONObject(operation.payload).getString("paymentId")}/allocations/${operation.entityId}"
        "MOVEMENT" -> "businesses/${operation.businessId}/movements/${operation.entityId}"
        else -> throw IllegalArgumentException("Unsupported sync entity type: ${operation.entityType}")
    }
}

/**
 * Which timestamp field each collection is ordered by. They do not share a name, so the puller
 * needs this table to build the range query and to stamp [RemoteDocument.watermarkEpochMillis].
 */
private data class PullSpec(val collection: String, val entityType: String, val watermarkField: String)

class FirestoreSyncGateway(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : SyncRemoteGateway, SyncRemotePullGateway, SyncRemoteBusinessLookup {

    /**
     * Queried by owner rather than read by id, because on a reinstalled phone the id is exactly what
     * was lost.
     *
     * An account can own several businesses — every reinstall before this existed minted another
     * one — so the newest wins. It is fetched unordered and compared here rather than with
     * `orderBy`, which would pair with the equality filter into a composite index that has to be
     * deployed before the query works at all; a person owns a handful of businesses, so sorting a
     * handful of documents in memory is the cheaper trade. Taking `limit(1)` without ordering was a
     * real bug: Firestore then answers by document id, which is a random UUID, so recovery landed on
     * whichever business happened to sort first — usually an empty one.
     */
    override fun findBusinessOwnedBy(userId: String): BusinessLookupResult = try {
        val newest = Tasks.await(
            firestore.collection("businesses")
                .whereEqualTo("ownerUserId", userId)
                .get()
        ).documents.maxByOrNull { (it.get("createdAtEpochMillis") as? Number)?.toLong() ?: 0L }
        if (newest == null) BusinessLookupResult.None else BusinessLookupResult.Found(newest.id)
    } catch (exception: Exception) {
        // Logged with the exception attached: Tasks.await wraps the Firestore status, and without
        // the stack trace the reinstall path was failing with nothing at all in logcat.
        Log.w(TAG, "Owner business lookup failed", exception)
        BusinessLookupResult.Failure(classify(exception))
    }

    override fun upload(operation: PendingSyncOperation): SyncUploadResult {
        // Resolved before the network try block on purpose. An unsupported entity type or a payload
        // missing its parent id throws IllegalArgumentException, and inside the try that surfaced
        // as a transient failure and was retried forever. Nothing about it can succeed on retry.
        val path = try {
            FirestoreDocumentPath.forOperation(operation)
        } catch (error: IllegalArgumentException) {
            return SyncUploadResult.Failure(SyncFailure.Permanent(error.message ?: "Unsupported sync operation"))
        }
        return try {
            Tasks.await(firestore.document(path).set(operation.payload.asFirestoreMap()))
            SyncUploadResult.Success
        } catch (exception: Exception) {
            SyncUploadResult.Failure(classify(exception))
        }
    }

    override fun download(businessId: String, sinceEpochMillis: Long): SyncDownloadResult = try {
        val root = firestore.collection("businesses").document(businessId)
        val documents = mutableListOf<RemoteDocument>()

        // The business document and the member list are both tiny and both gate everything else,
        // so they are fetched whole rather than by watermark.
        Tasks.await(root.get()).let { snapshot ->
            val data = snapshot.data
            if (snapshot.exists() && data != null) {
                documents += RemoteDocument(
                    entityType = "BUSINESS",
                    entityId = businessId,
                    watermarkEpochMillis = (data["updatedAtEpochMillis"] as? Number)?.toLong() ?: 0L,
                    fields = data,
                )
            }
        }
        Tasks.await(root.collection("members").get()).documents.forEach { snapshot ->
            val data = snapshot.data ?: return@forEach
            documents += RemoteDocument("MEMBER", snapshot.id, 0L, data)
        }

        PULL_SPECS.forEach { spec -> documents += pull(root, spec, sinceEpochMillis) }

        // Sale items and payment allocations live in subcollections and carry no timestamp of their
        // own. Fetching them per freshly pulled parent avoids collection-group indexes entirely.
        documents.filter { it.entityType == "SALE" }.forEach { sale ->
            documents += children(root.collection("sales").document(sale.entityId), "items", "SALE_ITEM", sale.watermarkEpochMillis)
        }
        documents.filter { it.entityType == "PAYMENT" }.forEach { payment ->
            documents += children(root.collection("payments").document(payment.entityId), "allocations", "PAYMENT_ALLOCATION", payment.watermarkEpochMillis)
        }

        SyncDownloadResult.Success(documents)
    } catch (exception: Exception) {
        Log.w(TAG, "Download failed", exception)
        SyncDownloadResult.Failure(classify(exception))
    }

    private fun pull(root: DocumentReference, spec: PullSpec, sinceEpochMillis: Long): List<RemoteDocument> =
        Tasks.await(
            root.collection(spec.collection)
                .whereGreaterThan(spec.watermarkField, sinceEpochMillis)
                .orderBy(spec.watermarkField, Query.Direction.ASCENDING)
                .limit(PAGE_SIZE)
                .get()
        ).documents.mapNotNull { snapshot ->
            val data = snapshot.data ?: return@mapNotNull null
            val watermark = (data[spec.watermarkField] as? Number)?.toLong() ?: return@mapNotNull null
            RemoteDocument(spec.entityType, snapshot.id, watermark, data)
        }

    private fun children(parent: DocumentReference, collection: String, entityType: String, watermark: Long): List<RemoteDocument> =
        Tasks.await(parent.collection(collection).get()).documents.mapNotNull { snapshot ->
            val data = snapshot.data ?: return@mapNotNull null
            RemoteDocument(entityType, snapshot.id, watermark, data)
        }

    /**
     * Classifies by Firestore's own status code instead of matching on the message text.
     *
     * The distinction decides whether a queued row is retried or parked forever, so deriving it
     * from a substring was a real hazard: INVALID_ARGUMENT and NOT_FOUND used to be treated as
     * transient and retried indefinitely. FAILED_PRECONDITION is what a missing composite index
     * reports — permanent is correct, since it cannot succeed until someone deploys the index.
     */
    private fun classify(exception: Exception): SyncFailure {
        val code = generateSequence(exception as Throwable?) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
            ?.code
        val message = exception.message ?: "Firestore call failed"
        return when (code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.INVALID_ARGUMENT,
            FirebaseFirestoreException.Code.NOT_FOUND,
            FirebaseFirestoreException.Code.ALREADY_EXISTS,
            FirebaseFirestoreException.Code.FAILED_PRECONDITION,
            FirebaseFirestoreException.Code.OUT_OF_RANGE,
            FirebaseFirestoreException.Code.UNIMPLEMENTED,
            FirebaseFirestoreException.Code.DATA_LOSS,
            -> SyncFailure.Permanent("$code: $message")

            // No Firestore code in the chain: the failure came from somewhere else entirely, so
            // fall back to the message. Keep this narrow.
            null -> if (message.contains("PERMISSION_DENIED", ignoreCase = true)) {
                SyncFailure.Permanent(message)
            } else {
                SyncFailure.Transient(message)
            }

            else -> SyncFailure.Transient("$code: $message")
        }
    }

    private companion object {
        const val TAG = "CuentivoSync"
        const val PAGE_SIZE = 200L

        val PULL_SPECS = listOf(
            PullSpec("customers", "CUSTOMER", "updatedAtEpochMillis"),
            PullSpec("products", "PRODUCT", "updatedAtEpochMillis"),
            PullSpec("sales", "SALE", "updatedAtEpochMillis"),
            PullSpec("payments", "PAYMENT", "createdAtEpochMillis"),
            PullSpec("movements", "MOVEMENT", "occurredAtEpochMillis"),
        )
    }
}

private fun String.asFirestoreMap(): Map<String, Any?> {
    val json = JSONObject(this)
    return json.keys().asSequence().associateWith { key ->
        val value = json.get(key)
        if (value == JSONObject.NULL) null else value
    }
}
