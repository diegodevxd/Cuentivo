package com.cuentivo.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.cuentivo.app.billing.EntitlementStore

object SyncScheduler {
    private const val UNIQUE_WORK_NAME = "cuentivo-sync"

    /**
     * Schedules a sync run — but only for an account entitled to the cloud.
     *
     * Cloud backup is a Pro benefit, and this is the first of two gates. It cannot be the only one:
     * work scheduled while Pro was active outlives the subscription, so [SyncWorker] asks again
     * before it does anything. Skipping here is silent and harmless — the outbox rows have already
     * been written, so nothing is lost and everything drains the moment Pro comes back.
     */
    fun enqueue(context: Context) {
        if (!EntitlementStore(context).allowsCloudSync()) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
