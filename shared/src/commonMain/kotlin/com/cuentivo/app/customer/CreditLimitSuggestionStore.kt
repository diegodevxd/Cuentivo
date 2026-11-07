package com.cuentivo.app.customer

import com.cuentivo.app.platform.KeyValueStore

/**
 * Remembers which credit-limit suggestion the owner already said "Ahora no" to, per customer.
 *
 * Keyed on customer id *and* the suggested amount, not just the customer id: if
 * `CreditLimitAdvisor` later suggests a different number — more settled sales, a strike that fell
 * outside its window — that is a new decision for the owner to make, not the one they already
 * dismissed. Local-only by design, same as `EntitlementStore`: this is a UI nicety, not a ledger
 * fact, so it never gets encoded onto `customers` or synced.
 */
class CreditLimitSuggestionStore(private val preferences: KeyValueStore) {

    fun dismiss(customerId: String, suggestedCents: Long) {
        preferences.edit { putLong(keyFor(customerId), suggestedCents) }
    }

    fun isDismissed(customerId: String, suggestedCents: Long): Boolean =
        preferences.getLong(keyFor(customerId), -1L) == suggestedCents

    private fun keyFor(customerId: String) = "dismissed_$customerId"

    companion object {
        const val PREFERENCES = "cuentivo.credit_limit_suggestions"
    }
}
