package com.cuentivo.app.sync

import com.cuentivo.app.data.local.CuentivoDatabase

/** Outcome of a restore attempt. Counts only, never names or amounts — this reaches logs and the UI. */
sealed interface CloudRestoreResult {
    /** The business came back, along with [customers] customers and [sales] sales. */
    data class Restored(val businessId: String, val customers: Int, val sales: Int) : CloudRestoreResult

    /** The account owns nothing in the cloud yet. A genuinely new user lands here. */
    data object NothingInCloud : CloudRestoreResult

    /** The lookup or the download failed. The device keeps its empty state and may try again. */
    data class Failed(val failure: SyncFailure) : CloudRestoreResult
}

/**
 * Brings a reinstalled phone back to the business its account already owns.
 *
 * The rest of sync only ever pulls for a businessId Room already holds, which is precisely what a
 * reinstall destroys. Without this step the app offers to *create* a business, mints a new random
 * id, and every customer already paid for stays stranded in Firestore under the previous one. So
 * this runs before that offer can be taken, and it goes from the account to the business rather
 * than the other way round.
 *
 * [RemoteMerger] does the actual writing and is idempotent, so a restore that dies halfway — or one
 * that runs twice — converges to the same database instead of duplicating anything. It also
 * advances the pull watermark itself, which is why nothing here touches sync_state.
 */
class CloudRestore(
    private val database: CuentivoDatabase,
    private val lookup: SyncRemoteBusinessLookup,
    private val gateway: SyncRemotePullGateway,
    private val membershipBootstrap: OwnerMembershipBootstrap,
) {

    suspend fun restore(userId: String): CloudRestoreResult {
        val businessId = when (val found = lookup.findBusinessOwnedBy(userId)) {
            is BusinessLookupResult.Found -> found.businessId
            BusinessLookupResult.None -> return CloudRestoreResult.NothingInCloud
            is BusinessLookupResult.Failure -> return CloudRestoreResult.Failed(found.failure)
        }

        // From zero, not from a watermark: this device has never seen any of it.
        val documents = when (val download = gateway.download(businessId, FROM_THE_BEGINNING)) {
            is SyncDownloadResult.Success -> download.documents
            is SyncDownloadResult.Failure -> return CloudRestoreResult.Failed(download.failure)
        }

        RemoteMerger(database).apply(businessId, documents)

        // The business row only exists locally once the merge has adopted it. Republishing the owner
        // membership from here matters because the rules gate every other collection behind it, and
        // this device's outbox is empty — nothing else would ever enqueue it again.
        database.businessDao().byId(businessId)?.let { membershipBootstrap.ensure(it) }

        return CloudRestoreResult.Restored(
            businessId = businessId,
            customers = documents.count { it.entityType == "CUSTOMER" },
            sales = documents.count { it.entityType == "SALE" },
        )
    }

    private companion object {
        const val FROM_THE_BEGINNING = 0L
    }
}
