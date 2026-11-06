package com.cuentivo.app.ui.onboarding

import com.cuentivo.app.domain.Money

/**
 * Pure policy for the "¿Quién te debe?" first moment of value onboarding screen.
 *
 * Rules:
 * 1. Displayed only when the ledger has no customers, no financial movements, and the user has
 *    not dismissed or skipped the prompt.
 * 2. Submission requires a non-blank customer name and a strictly positive monetary amount.
 * 3. Never blocks the user: skipping immediately dismisses the prompt and opens the app.
 */
object FirstDebtPolicy {

    fun shouldShow(
        customerCount: Int,
        isDismissed: Boolean,
        hasFinancialActivity: Boolean = false,
    ): Boolean {
        if (isDismissed) return false
        if (customerCount > 0) return false
        if (hasFinancialActivity) return false
        return true
    }

    fun canSubmit(
        name: String,
        amountText: String,
        isSaving: Boolean = false,
    ): Boolean {
        if (isSaving) return false
        if (name.trim().isEmpty()) return false
        val amountCents = Money.positiveCentsFromPesos(amountText.trim())
        return amountCents != null && amountCents > 0L
    }
}