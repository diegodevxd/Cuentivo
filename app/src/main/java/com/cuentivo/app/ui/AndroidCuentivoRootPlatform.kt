package com.cuentivo.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.cuentivo.app.backup.DatabaseBackup
import com.cuentivo.app.billing.BillingGateway
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.ProductImageStore
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.notifications.CollectionReminderScheduler
import com.cuentivo.app.sync.FirestoreDeletionGateway
import com.cuentivo.app.sync.FirestoreSyncGateway

class AndroidReminderControl(private val context: Context) : ReminderControl {
    override fun sync() {
        CollectionReminderScheduler.sync(context)
    }

    override fun cancel() {
        CollectionReminderScheduler.cancel(context)
    }
}

class AndroidPurchaseControl(
    private val billing: BillingGateway,
    private val activity: Activity,
) : PurchaseControl {
    override fun refresh(userId: String?) {
        billing.refresh(userId)
    }

    override fun launchPurchase(userId: String?, targetTier: SubscriptionTier, isAnonymous: Boolean) {
        // The real Activity, not applicationContext: Play needs somewhere to put its sheet.
        billing.launchPurchase(
            activity = activity,
            userId = userId,
            targetTier = targetTier,
            isAnonymous = isAnonymous,
        )
    }
}

class AndroidBackupControl(
    private val context: Context,
    private val database: CuentivoDatabase,
    private val onRestoredCallback: () -> Unit,
) : BackupControl {
    override fun suggestedFileName(todayIso: String): String = DatabaseBackup.suggestedFileName(todayIso)

    override fun export(password: CharArray): ByteArray =
        DatabaseBackup.export(context, database, password)

    override fun restore(file: ByteArray, password: CharArray) {
        DatabaseBackup.restore(context, database, file, password)
    }

    override fun onRestored() {
        onRestoredCallback()
    }
}

class AndroidBusinessErasure(
    private val syncGateway: FirestoreSyncGateway,
    private val deletionGateway: FirestoreDeletionGateway,
) : BusinessErasure {
    override fun requestBusinessDeletion(businessId: String, nowEpochMillis: Long): Result<Unit> =
        syncGateway.requestBusinessDeletion(businessId, nowEpochMillis)

    override fun deleteBusiness(businessId: String): Result<Unit> =
        deletionGateway.deleteBusiness(businessId)
}

/** The same launcher the screen used to create inline, now reachable from the shared root. */
@Composable
fun rememberAndroidReminderPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return {
        // Sólo Android 13+ tiene permiso de notificaciones; debajo, el aviso simplemente funciona.
        if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else onResult(true)
    }
}

/** The Android implementations, wired exactly where the Activity used to call them. */
fun androidCuentivoRootPlatform(
    activity: Activity,
    database: CuentivoDatabase,
    billing: BillingGateway,
    firestoreGateway: FirestoreSyncGateway,
    deletionGateway: FirestoreDeletionGateway,
    appScope: kotlinx.coroutines.CoroutineScope,
    isDebugBuild: Boolean,
    sharer: com.cuentivo.app.platform.Sharer,
    onRestored: () -> Unit,
): CuentivoRootPlatform {
    val context = activity.applicationContext
    val productImages = ProductImageStore(context)
    return CuentivoRootPlatform(
        appScope = appScope,
        isDebugBuild = isDebugBuild,
        authHost = activity,
        sharer = sharer,
        reminders = AndroidReminderControl(context),
        rememberReminderPermissionRequest = { onResult ->
            rememberAndroidReminderPermissionRequest(onResult)
        },
        purchases = AndroidPurchaseControl(billing, activity),
        backup = AndroidBackupControl(context, database, onRestored),
        deleteProductPhoto = { fileName -> productImages.delete(fileName) },
        clearLocalData = { database.clearAllTables() },
        rememberAppPlatform = { rememberAndroidCuentivoAppPlatform() },
    )
}
