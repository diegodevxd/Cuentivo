package com.cuentivo.app.sync

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.cuentivo.app.domain.BusinessClaimPolicy
import com.cuentivo.app.domain.BusinessName
import com.cuentivo.app.domain.DeletionRequestPolicy
import com.cuentivo.app.domain.RemoteBusiness
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
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
    /**
     * Stamps the business as "the owner asked for this to be erased", and waits for the server.
     *
     * Deliberately **not** routed through the outbox like every other write. The outbox is drained
     * later by a WorkManager job, and the very next thing this flow does is delete the Firebase Auth
     * account — after which there is no credential left to authenticate that job, so the request
     * would fail permanently and silently. It has to land while the account still exists.
     *
     * This step only asks — and it must be safe to ask again. The flag is read from the server
     * ([Source.SERVER], never the cache, for the same reason the rest of this class does) and
     * [DeletionRequestPolicy] decides what to do: a business document that no longer exists is an
     * erasure a prior attempt already finished, a flag that is already on the document means the
     * request already landed (the rules make it immutable, so rewriting it with a fresh timestamp
     * would be denied and strand the erasure forever), and only an absent flag is written — once,
     * as a merge so no other field is touched.
     *
     * Once the flag is on the document, the deletion rules open up: the same verified owner may then
     * delete the entire business through [FirestoreDeletionGateway], which runs the subcollection
     * cascade (children first, batches of at most 500, business document last). Setting the flag is
     * what the server watches for — it is the one-way door.
     */
    fun requestBusinessDeletion(businessId: String, nowEpochMillis: Long): Result<Unit> = try {
        val document = firestore.collection("businesses").document(businessId)
        // The decision hangs on whether the server already holds the flag, and the flag is
        // immutable: a stale cache could make a retry rewrite the timestamp and get denied forever.
        val snapshot = Tasks.await(document.get(Source.SERVER))
        when (DeletionRequestPolicy.decide(snapshot.exists(), snapshot.getLong("deletionRequestedAtEpochMillis"))) {
            // The business document is gone: a prior cascade already erased it. Nothing to write.
            DeletionRequestPolicy.Outcome.AlreadyGone -> Unit
            // The owner already asked: the flag is immutable, so a retry must not rewrite it. The
            // original marker stays, and the retry proceeds to the cascade.
            is DeletionRequestPolicy.Outcome.AlreadyRequested -> Unit
            // First request: write the flag once. A merge, not update and not a full set, so no
            // other field of the business document is touched.
            DeletionRequestPolicy.Outcome.NeedsWrite -> Tasks.await(
                document.set(mapOf("deletionRequestedAtEpochMillis" to nowEpochMillis), SetOptions.merge())
            )
        }
        Result.success(Unit)
    } catch (error: Exception) {
        // Never rethrown into the delete flow: a phone with no signal must still be able to wipe
        // itself. The caller tells the person their cloud copy is still pending.
        Log.w(TAG, "Could not flag the business for deletion", error)
        Result.failure(error)
    }

    override fun findBusinessOwnedBy(userId: String): BusinessLookupResult = try {
        val snapshots = Tasks.await(
            firestore.collection("businesses")
                .whereEqualTo("ownerUserId", userId)
                // Source.SERVER, and this is the whole point of the query rather than a detail.
                // The default source answers from the local cache whenever the server is
                // unreachable, and it answers *without an error*: an offline phone gets an empty
                // snapshot, not a failure. On a freshly installed device that cache is empty by
                // definition, so "I could not ask" arrived here disguised as "this account owns
                // nothing" — the exact confusion BusinessClaim.Blocked exists to prevent, sailing
                // straight past it. The Blocked screen was therefore mostly unreachable by the one
                // cause it was written for, and a reinstall with no signal could still mint the
                // duplicate that stranded a ledger. Asking the server explicitly makes offline
                // throw, which is what the caller already knows how to handle correctly.
                .get(Source.SERVER)
        ).documents.associateBy { it.id }
        val owned = snapshots.values.map {
            RemoteBusiness(it.id, (it.get("createdAtEpochMillis") as? Number)?.toLong() ?: 0L)
        }
        // Owning more than one is a bug upstream, not a normal state. Logged without ids so the
        // signal survives without putting anything identifying in logcat.
        if (owned.size > 1) Log.w(TAG, "This account owns ${owned.size} businesses; adopting the oldest.")
        // The oldest, not the newest. Taking the newest was backwards: a duplicate is by definition
        // the one created later, so that handed the account its own mistake and left the ledger
        // with the real history unreachable — every query filters by businessId.
        val chosen = BusinessClaimPolicy.preferred(owned)
        if (chosen == null) {
            BusinessLookupResult.None
        } else {
            val document = snapshots.getValue(chosen.businessId)
            BusinessLookupResult.Found(
                businessId = chosen.businessId,
                name = document.getString("name") ?: BusinessName.DEFAULT,
                currency = document.getString("currency") ?: "MXN",
                timezone = document.getString("timezone") ?: "America/Mexico_City",
                createdAtEpochMillis = chosen.createdAtEpochMillis,
                updatedAtEpochMillis = (document.get("updatedAtEpochMillis") as? Number)?.toLong() ?: 0L,
            )
        }
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
            val failure = classify(exception)
            val worthChecking = SyncRemoteImmutability.shouldCheckWhetherAlreadyPublished(
                entityType = operation.entityType,
                payload = operation.payload,
                isPermissionDenied = isPermissionDenied(exception),
            )
            if (worthChecking) {
                resolveAgainstPublishedCopy(path, operation.payload, failure)
            } else {
                SyncUploadResult.Failure(failure)
            }
        }
    }

    /**
     * Decides what a denial means once the server has been asked what is actually up there.
     *
     * Three answers, and they are genuinely different. The same document already published: the row
     * is finished. A different document under the same id: these are never rewritable, so the row
     * can never succeed and is parked saying exactly that, instead of hiding a divergence nobody
     * would ever look at again. Nothing there at all: the denial was real, and it stands.
     *
     * Asked of the server, never the cache -- on the phone that showed this, the cache is precisely
     * what could not be trusted. Only reached after a denial, so the normal path pays nothing.
     */
    private fun resolveAgainstPublishedCopy(
        path: String,
        payload: String,
        denial: SyncFailure,
    ): SyncUploadResult {
        val snapshot = try {
            Tasks.await(firestore.document(path).get(Source.SERVER))
        } catch (error: Exception) {
            // Could not ask. That is not the same as "will never work", and parking it permanently
            // on an unreachable network would strand the row for good over a temporary outage.
            return SyncUploadResult.Failure(SyncFailure.Transient(error.message ?: "could not read the published copy"))
        }
        val remote = snapshot.data ?: return SyncUploadResult.Failure(denial)
        return if (SyncRemoteImmutability.isAlreadyTheSameDocument(payload.asFirestoreMap(), remote)) {
            SyncUploadResult.Success
        } else {
            SyncUploadResult.Failure(
                SyncFailure.Permanent("already published with different content at $path"),
            )
        }
    }

    private fun isPermissionDenied(exception: Exception): Boolean =
        generateSequence(exception as Throwable?) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
            ?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED

    override fun download(businessId: String, sinceEpochMillis: Long): SyncDownloadResult = try {
        val root = firestore.collection("businesses").document(businessId)
        val documents = mutableListOf<RemoteDocument>()

        // Every read below is Source.SERVER, for the same reason findBusinessOwnedBy is, and it
        // matters most here. The default source falls back to the local cache whenever the server
        // is unreachable and answers *without an error* — so a Pro restore that lost signal partway
        // returned an empty document list and was reported as success. The caller read that as
        // NothingInCloud, set restoreAttempted and never asked again: the person ended up with the
        // business adopted and the ledger empty, believing their data was gone. The one place in
        // the app where a silent empty answer costs a user their history. Asking the server
        // explicitly makes offline throw, which classify() turns into a transient failure that is
        // retried instead of being mistaken for an answer.
        //
        // The ordinary background pull pays for this too: with no signal it now fails loudly rather
        // than merging the cache into itself, which was a no-op anyway.
        Tasks.await(root.get(Source.SERVER)).let { snapshot ->
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
        Tasks.await(root.collection("members").get(Source.SERVER)).documents.forEach { snapshot ->
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
                .get(Source.SERVER)
        ).documents.mapNotNull { snapshot ->
            val data = snapshot.data ?: return@mapNotNull null
            val watermark = (data[spec.watermarkField] as? Number)?.toLong() ?: return@mapNotNull null
            RemoteDocument(spec.entityType, snapshot.id, watermark, data)
        }

    private fun children(parent: DocumentReference, collection: String, entityType: String, watermark: Long): List<RemoteDocument> =
        Tasks.await(parent.collection(collection).get(Source.SERVER)).documents.mapNotNull { snapshot ->
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
