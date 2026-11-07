package com.cuentivo.app.customer

import android.content.Context
import com.cuentivo.app.platform.SharedPreferencesKeyValueStore

fun CreditLimitSuggestionStore(context: Context): CreditLimitSuggestionStore =
    CreditLimitSuggestionStore(
        SharedPreferencesKeyValueStore(context, CreditLimitSuggestionStore.PREFERENCES),
    )
