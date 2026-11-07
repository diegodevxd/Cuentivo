package com.cuentivo.app.auth

import android.content.Context
import com.cuentivo.app.platform.SharedPreferencesKeyValueStore

/**
 * La misma llamada de siempre para Android: `PendingAccountDeletionStore(context)`.
 *
 * El constructor con `Context` no puede vivir en común, así que vive aquí y no cambia ni una llamada
 * del lado de Android. El archivo de preferencias y la clave se conservan tal cual.
 */
fun PendingAccountDeletionStore(context: Context): PendingAccountDeletionStore =
    PendingAccountDeletionStore(SharedPreferencesKeyValueStore(context, PendingAccountDeletionStore.PREFERENCES))
