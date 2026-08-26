package com.cuentivo.app.auth

/**
 * Pure policy determining whether switching from an active session to a different account
 * requires an explicit user confirmation dialog to protect unsaved local guest data.
 *
 * Golden rule of identity and local data:
 * In case of doubt, preserve local data and ask the user.
 * NEVER delete to resolve an identity conflict.
 */
object GuestAccountSwitchPolicy {

    /**
     * Decides whether an explicit confirmation warning must be shown before signing in
     * with an existing or different account.
     *
     * @param isAnonymous whether the current session is an anonymous guest session.
     * @param hasLocalData whether the local ledger contains unsaved data (e.g. customers, sales).
     * @return true if an explicit confirmation warning MUST be shown before touching the session.
     */
    fun shouldConfirmAccountSwitch(
        isAnonymous: Boolean,
        hasLocalData: Boolean,
    ): Boolean {
        return isAnonymous && hasLocalData
    }

    /**
     * Overload taking customer count and financial activity.
     */
    fun shouldConfirmAccountSwitch(
        isAnonymous: Boolean,
        customerCount: Int,
        hasFinancialActivity: Boolean = false,
    ): Boolean {
        val hasLocalData = customerCount > 0 || hasFinancialActivity
        return shouldConfirmAccountSwitch(isAnonymous = isAnonymous, hasLocalData = hasLocalData)
    }
}