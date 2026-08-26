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

    /**
     * Resolves how to proceed when an account collision occurs (e.g. email or Google account already exists).
     *
     * @param isAnonymous whether the current session is an anonymous guest session.
     * @param hasLocalData whether the local guest session has data at risk.
     * @return [AccountCollisionResolution.CONFIRM_BEFORE_SIGN_IN] if a confirmation dialog must be shown,
     *         or [AccountCollisionResolution.DIRECT_SIGN_IN] if it can proceed directly without asking.
     */
    fun resolveCollisionAction(
        isAnonymous: Boolean,
        hasLocalData: Boolean,
    ): AccountCollisionResolution {
        return if (shouldConfirmAccountSwitch(isAnonymous = isAnonymous, hasLocalData = hasLocalData)) {
            AccountCollisionResolution.CONFIRM_BEFORE_SIGN_IN
        } else {
            AccountCollisionResolution.DIRECT_SIGN_IN
        }
    }

    /**
     * Overload taking customer count and financial activity.
     */
    fun resolveCollisionAction(
        isAnonymous: Boolean,
        customerCount: Int,
        hasFinancialActivity: Boolean = false,
    ): AccountCollisionResolution {
        val hasLocalData = customerCount > 0 || hasFinancialActivity
        return resolveCollisionAction(isAnonymous = isAnonymous, hasLocalData = hasLocalData)
    }
}

/**
 * The action to take when an account collision is encountered.
 */
enum class AccountCollisionResolution {
    /** Prompt with explicit confirmation dialog before wiping guest data and signing in. */
    CONFIRM_BEFORE_SIGN_IN,

    /** Switch directly to sign in because there is no local guest data to lose. */
    DIRECT_SIGN_IN,
}