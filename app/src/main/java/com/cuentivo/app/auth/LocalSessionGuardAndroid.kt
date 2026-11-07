package com.cuentivo.app.auth

import android.content.Context
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.platform.SharedPreferencesKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun LocalSessionGuard(context: Context, database: CuentivoDatabase): LocalSessionGuard =
    LocalSessionGuard(
        preferences = SharedPreferencesKeyValueStore(context, LocalSessionGuard.PREFERENCES),
        firstBusiness = { database.businessDao().first() },
        clearAllTables = { withContext(Dispatchers.IO) { database.clearAllTables() } },
    )
