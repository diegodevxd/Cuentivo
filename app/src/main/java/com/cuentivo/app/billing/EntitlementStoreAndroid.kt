package com.cuentivo.app.billing

import android.content.Context
import com.cuentivo.app.platform.SharedPreferencesKeyValueStore
import com.google.firebase.auth.FirebaseAuth

fun EntitlementStore(context: Context): EntitlementStore =
    EntitlementStore(SharedPreferencesKeyValueStore(context, EntitlementStore.PREFERENCES))

/** Lo que antes eran los valores por defecto del método: la sesión actual de Firebase. */
fun EntitlementStore.allowsCloudSync(): Boolean {
    val user = FirebaseAuth.getInstance().currentUser
    return allowsCloudSync(
        userId = user?.uid,
        isEmailVerified = user?.isEmailVerified == true,
    )
}
