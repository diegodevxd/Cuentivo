package com.cuentivo.app.sync

import android.util.Log
import com.cuentivo.app.data.local.CuentivoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which half of the restore broke. Both used to collapse into one message, which cost three rounds
 * of guessing: "it failed" says nothing about whether the account could not be *found* or could not
 * be *read*, and those have opposite fixes. */
enum class CloudRestoreStage { LOOKUP, DOWNLOAD }

/** Outcome of a restore attempt. Counts only, never names or amounts — this reaches logs and the UI. */
sealed interface CloudRestoreResult {
    /** The business came back, along with [customers] customers and [sales] sales. */
    data class Restored(val businessId: String, val customers: Int, val sales: Int) : CloudRestoreResult

    /** The account owns nothing in the cloud yet. A genuinely new user lands here. */
    data object NothingInCloud : CloudRestoreResult

    /** The lookup or the download failed. The device keeps its empty state and may try again. */
    data class Failed(val failure: SyncFailure, val stage: CloudRestoreStage) : CloudRestoreResult
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

    /**
     * Runs off the main thread, always. The gateway reaches Firestore through blocking
     * `Tasks.await(...)`, which Play Services refuses outright on the main thread — and the only
     * caller is a Compose `LaunchedEffect`, which starts on `Dispatchers.Main`. That combination
     * failed on the very first call with "must not be called on the main application thread",
     * before a single byte reached the network, which is also why logcat had nothing to show. The
     * dispatcher belongs here rather than at the call site: every path into this class is blocking,
     * so no caller should have to know that.
     */
    suspend fun restore(userId: String): CloudRestoreResult = withContext(Dispatchers.IO) {
        val businessId = when (val found = lookup.findBusinessOwnedBy(userId)) {
            is BusinessLookupResult.Found -> found.businessId
            BusinessLookupResult.None -> return@withContext CloudRestoreResult.NothingInCloud
            is BusinessLookupResult.Failure ->
                return@withContext failed(found.failure, CloudRestoreStage.LOOKUP)
        }

        // From zero, not from a watermark: this device has never seen any of it.
        val documents = when (val download = gateway.download(businessId, FROM_THE_BEGINNING)) {
            is SyncDownloadResult.Success -> download.documents
            is SyncDownloadResult.Failure ->
                return@withContext failed(download.failure, CloudRestoreStage.DOWNLOAD)
        }

        RemoteMerger(database).apply(businessId, documents)

        // The business row only exists locally once the merge has adopted it. Republishing the owner
        // membership from here matters because the rules gate every other collection behind it, and
        // this device's outbox is empty — nothing else would ever enqueue it again.
        database.businessDao().byId(businessId)?.let { membershipBootstrap.ensure(it) }

        CloudRestoreResult.Restored(
            businessId = businessId,
            customers = documents.count { it.entityType == "CUSTOMER" },
            sales = documents.count { it.entityType == "SALE" },
        )
    }

    /**
     * The one place a restore failure is recorded. Only the stage and Firestore's own status text
     * go out — no uid, no business id, no names or amounts — so this stays inside the no-PII rule
     * while still naming the cause.
     */
    private fun failed(failure: SyncFailure, stage: CloudRestoreStage): CloudRestoreResult.Failed {
        Log.w(TAG, "Cloud restore failed at $stage: ${failure.message}")
        return CloudRestoreResult.Failed(failure, stage)
    }

    private companion object {
        const val FROM_THE_BEGINNING = 0L
        const val TAG = "CuentivoRestore"
    }
}
