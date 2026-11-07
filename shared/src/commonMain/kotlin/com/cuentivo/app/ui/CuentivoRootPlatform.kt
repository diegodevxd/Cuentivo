package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import com.cuentivo.app.auth.PlatformAuthHost
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.platform.Sharer
import kotlinx.coroutines.CoroutineScope

interface ReminderControl {
    fun sync()
    fun cancel()
}

interface PurchaseControl {
    fun refresh(userId: String?)
    fun launchPurchase(userId: String?, targetTier: SubscriptionTier, isAnonymous: Boolean)
}

interface BackupControl {
    fun suggestedFileName(todayIso: String): String
    /** Bloqueantes: la raíz las llama dentro de `withContext(databaseDispatcher)`. */
    fun export(password: CharArray): ByteArray
    fun restore(file: ByteArray, password: CharArray)
    /** La base ya es otra: Android reinicia el proceso; iOS la reabrirá (F5-P). */
    fun onRestored()
}

/** Las dos operaciones remotas del borrado de cuenta. Bloqueantes, como hoy. */
interface BusinessErasure {
    fun requestBusinessDeletion(businessId: String, nowEpochMillis: Long): Result<Unit>
    fun deleteBusiness(businessId: String): Result<Unit>
}

class CuentivoRootPlatform(
    val appScope: CoroutineScope,
    val isDebugBuild: Boolean,
    val authHost: PlatformAuthHost,
    val sharer: Sharer,
    val reminders: ReminderControl,
    /** Devuelve la acción de pedir permiso. Se llama siempre en el mismo punto de la composición. */
    val rememberReminderPermissionRequest: @Composable (onResult: (Boolean) -> Unit) -> (() -> Unit),
    val purchases: PurchaseControl,
    val backup: BackupControl,
    val deleteProductPhoto: (String) -> Unit,
    val clearLocalData: suspend () -> Unit,
    val rememberAppPlatform: @Composable () -> CuentivoAppPlatform,
)
