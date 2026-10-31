package com.cuentivo.app.ui.onboarding

import android.content.Context
import android.content.SharedPreferences

/**
 * Records whether the first-debt onboarding question has been answered or dismissed by the user.
 *
 * Scoped by userId so switching accounts (or completing it on one) does not suppress it for a new
 * guest session.
 */
class FirstDebtPreferenceStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isDismissed(userId: String): Boolean {
        if (userId.isBlank()) return false
        return preferences.getBoolean(keyFor(userId), false)
    }

    fun markDismissed(userId: String) {
        if (userId.isBlank()) return
        preferences.edit().putBoolean(keyFor(userId), true).apply()
    }

    private fun keyFor(userId: String): String = "first_debt_dismissed_$userId"

    companion object {
        private const val PREFERENCES = "cuentivo_onboarding_preferences"
    }
}