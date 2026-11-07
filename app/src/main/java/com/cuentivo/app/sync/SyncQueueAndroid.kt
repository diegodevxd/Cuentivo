package com.cuentivo.app.sync

import android.content.Context
import com.cuentivo.app.data.local.SyncOperationDao

fun SyncQueue(
    dao: SyncOperationDao,
    context: Context,
    allowsCloudSync: () -> Boolean = { true },
): SyncQueue = SyncQueue(
    dao,
    scheduleSync = { SyncScheduler.enqueue(context) },
    allowsCloudSync = allowsCloudSync,
)
