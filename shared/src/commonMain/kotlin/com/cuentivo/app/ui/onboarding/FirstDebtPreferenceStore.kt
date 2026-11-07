package com.cuentivo.app.ui.onboarding

/** Donde vive la respuesta, tras una interfaz para que las pruebas comunes la manejen. */
interface FirstDebtFlagStore {
    fun isSet(key: String): Boolean
    fun set(key: String)
}

/**
 * Records whether the first-debt onboarding question has been answered or dismissed by the user.
 *
 * Scoped by userId so switching accounts (or completing it on one) does not suppress it for a new
 * guest session.
 */
class FirstDebtPreferenceStore(private val flags: FirstDebtFlagStore) {
    fun isDismissed(userId: String): Boolean {
        if (userId.isBlank()) return false
        return flags.isSet(keyFor(userId))
    }

    fun markDismissed(userId: String) {
        if (userId.isBlank()) return
        flags.set(keyFor(userId))
    }

    private fun keyFor(userId: String): String = "first_debt_dismissed_$userId"
}
