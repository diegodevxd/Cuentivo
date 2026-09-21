package com.cuentivo.app.sync

import com.cuentivo.app.domain.BusinessName
import java.security.MessageDigest

object SyncOperationId {
    fun create(businessId: String, entityType: String, entityId: String, revision: Long): String {
        require(businessId.isNotBlank() && entityType.isNotBlank() && entityId.isNotBlank())
        val input = "$businessId|$entityType|$entityId|$revision"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

enum class MergeDecision { ApplyRemote, KeepLocal, NoChange, Conflict }

object RemoteMergePolicy {
    fun decide(localUpdatedAt: Long, remoteUpdatedAt: Long, sameContent: Boolean): MergeDecision = when {
        sameContent -> MergeDecision.NoChange
        remoteUpdatedAt > localUpdatedAt -> MergeDecision.ApplyRemote
        localUpdatedAt > remoteUpdatedAt -> MergeDecision.KeepLocal
        else -> MergeDecision.Conflict
    }
}

sealed interface SyncFailure {
    val message: String
    data class Transient(override val message: String) : SyncFailure
    data class Permanent(override val message: String) : SyncFailure
}

object SyncFailurePolicy {
    fun shouldRetry(failure: SyncFailure): Boolean = failure is SyncFailure.Transient
}

data class PendingSyncOperation(
    val id: String,
    val businessId: String,
    val entityType: String,
    val entityId: String,
    val payload: String,
)

sealed interface SyncUploadResult {
    data object Success : SyncUploadResult
    data class Failure(val failure: SyncFailure) : SyncUploadResult
}

interface SyncRemoteGateway {
    fun upload(operation: PendingSyncOperation): SyncUploadResult
}

/**
 * A document read back from the remote store.
 *
 * [watermarkEpochMillis] is whichever timestamp column the collection is ordered by — customers and
 * products use updatedAtEpochMillis, payments use createdAtEpochMillis, movements use
 * occurredAtEpochMillis. It exists so the puller can advance a single high-water mark across
 * collections that do not share a field name.
 */
data class RemoteDocument(
    val entityType: String,
    val entityId: String,
    val watermarkEpochMillis: Long,
    val fields: Map<String, Any?>,
)

sealed interface SyncDownloadResult {
    data class Success(val documents: List<RemoteDocument>) : SyncDownloadResult
    data class Failure(val failure: SyncFailure) : SyncDownloadResult
}

/**
 * Kept separate from [SyncRemoteGateway] so an upload-only fake stays valid.
 */
interface SyncRemotePullGateway {
    fun download(businessId: String, sinceEpochMillis: Long): SyncDownloadResult
}

sealed interface BusinessLookupResult {
    /**
     * The account owns [businessId] in the cloud.
     *
     * The descriptive fields ride along so a **free** account can adopt the id without downloading
     * anything: it writes the business row locally and keeps working under the id it already owns,
     * instead of minting a second one. Pulling the ledger itself stays a Pro feature.
     *
     * The timestamps default to zero on purpose. An adopted row that claims to be older than the
     * remote copy can only lose the `RemoteMergePolicy` comparison, so a caller that does not
     * supply them can never overwrite a name someone actually chose.
     */
    data class Found(
        val businessId: String,
        val name: String = BusinessName.DEFAULT,
        val currency: String = "MXN",
        val timezone: String = "America/Mexico_City",
        val createdAtEpochMillis: Long = 0L,
        val updatedAtEpochMillis: Long = 0L,
    ) : BusinessLookupResult

    /** The account is real but owns no business remotely — a genuinely new user, not an error. */
    data object None : BusinessLookupResult

    data class Failure(val failure: SyncFailure) : BusinessLookupResult
}

/**
 * Finds the business an account already owns in the cloud, given only the uid.
 *
 * Everything else in sync starts from a businessId this device already knows. A phone that has just
 * been reinstalled knows nothing: Room is empty, and creating a business would mint a fresh random
 * id that no remote document is filed under, stranding the real data forever under the old id. This
 * is the one lookup that goes the other way — from the account to the business — and it exists so a
 * reinstall can find its way back rather than silently starting over.
 */
interface SyncRemoteBusinessLookup {
    fun findBusinessOwnedBy(userId: String): BusinessLookupResult
}

/** What Room already knows about an incoming remote document. */
data class LocalRecord(
    val updatedAtEpochMillis: Long,
    val isCanceled: Boolean = false,
)

sealed interface RemoteMergeAction {
    /** Absent locally: insert it. */
    data object Adopt : RemoteMergeAction

    /** Mutable entity whose remote copy is strictly newer. */
    data object Overwrite : RemoteMergeAction

    /**
     * Immutable entity already present locally, but the remote copy records a cancellation this
     * device has not seen. Only the cancellation flag is applied; the row itself is never rewritten.
     */
    data object ApplyCancellation : RemoteMergeAction

    data class Skip(val reason: String) : RemoteMergeAction
}

/**
 * Decides what a remote document is allowed to do to the local database.
 *
 * The split is the whole point: descriptive records (business, member, customer, product) may be
 * overwritten by a newer remote copy, while financial records (sale, item, payment, allocation,
 * movement) may only ever be inserted when missing. A remote device can therefore never rewrite
 * or erase a ledger row that already exists here — corrections arrive as their own compensating
 * movement documents, exactly as they do locally.
 */
object RemoteMergePlanner {
    private val MUTABLE = setOf("BUSINESS", "MEMBER", "CUSTOMER", "PRODUCT")
    private val IMMUTABLE = setOf("SALE", "SALE_ITEM", "PAYMENT", "PAYMENT_ALLOCATION", "MOVEMENT")

    fun plan(
        entityType: String,
        remoteUpdatedAtEpochMillis: Long,
        remoteCanceled: Boolean,
        local: LocalRecord?,
        sameContent: Boolean,
    ): RemoteMergeAction = when {
        entityType !in MUTABLE && entityType !in IMMUTABLE ->
            RemoteMergeAction.Skip("unsupported entity type: $entityType")

        local == null -> RemoteMergeAction.Adopt

        entityType in IMMUTABLE ->
            if (remoteCanceled && !local.isCanceled) {
                RemoteMergeAction.ApplyCancellation
            } else {
                RemoteMergeAction.Skip("immutable record already present")
            }

        else -> when (RemoteMergePolicy.decide(local.updatedAtEpochMillis, remoteUpdatedAtEpochMillis, sameContent)) {
            MergeDecision.ApplyRemote -> RemoteMergeAction.Overwrite
            MergeDecision.NoChange -> RemoteMergeAction.Skip("identical content")
            MergeDecision.KeepLocal -> RemoteMergeAction.Skip("local copy is newer")
            // Same timestamp, different content. Keeping local is the deterministic choice and
            // loses nothing: the local row is still queued for upload, so both devices reconverge
            // on it rather than on whichever one happened to be pulled last.
            MergeDecision.Conflict -> RemoteMergeAction.Skip("conflicting timestamps, keeping local")
        }
    }
}

data class SyncProcessResult(
    val completedIds: List<String>,
    val retryIds: List<String>,
    val permanentFailures: Map<String, String>,
)

class SyncCoordinator(private val gateway: SyncRemoteGateway) {
    fun process(operations: List<PendingSyncOperation>): SyncProcessResult {
        val completed = mutableListOf<String>()
        val retries = mutableListOf<String>()
        val permanentFailures = mutableMapOf<String, String>()
        operations.forEach { operation ->
            when (val result = gateway.upload(operation)) {
                SyncUploadResult.Success -> completed += operation.id
                is SyncUploadResult.Failure -> if (SyncFailurePolicy.shouldRetry(result.failure)) {
                    retries += operation.id
                } else {
                    permanentFailures[operation.id] = result.failure.message
                }
            }
        }
        return SyncProcessResult(completed, retries, permanentFailures)
    }
}
