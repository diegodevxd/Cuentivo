package com.cuentivo.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GuestAccountSwitchPolicy].
 *
 * Verifies that an anonymous guest session with local data is always asked for confirmation
 * before switching to a different account, while an empty session or an authenticated account
 * does not show an unnecessary warning.
 */
class GuestAccountSwitchPolicyTest {

    @Test
    fun guest_with_customers_requires_confirmation() {
        val requiresConfirmation = GuestAccountSwitchPolicy.shouldConfirmAccountSwitch(
            isAnonymous = true,
            customerCount = 5,
            hasFinancialActivity = false,
        )
        assertTrue("Un invitado con clientes debe recibir confirmación antes de cambiar de cuenta", requiresConfirmation)
    }

    @Test
    fun guest_with_financial_activity_requires_confirmation() {
        val requiresConfirmation = GuestAccountSwitchPolicy.shouldConfirmAccountSwitch(
            isAnonymous = true,
            customerCount = 0,
            hasFinancialActivity = true,
        )
        assertTrue("Un invitado con actividad financiera debe recibir confirmación", requiresConfirmation)
    }

    @Test
    fun guest_without_local_data_does_not_require_confirmation() {
        val requiresConfirmation = GuestAccountSwitchPolicy.shouldConfirmAccountSwitch(
            isAnonymous = true,
            customerCount = 0,
            hasFinancialActivity = false,
        )
        assertFalse("Un invitado sin datos no necesita advertencia", requiresConfirmation)
    }

    @Test
    fun authenticated_account_with_data_does_not_require_guest_confirmation() {
        val requiresConfirmation = GuestAccountSwitchPolicy.shouldConfirmAccountSwitch(
            isAnonymous = false,
            customerCount = 10,
            hasFinancialActivity = true,
        )
        assertFalse("Una cuenta registrada normal no aplica para advertencia de invitado", requiresConfirmation)
    }

    @Test
    fun authenticated_account_without_data_does_not_require_guest_confirmation() {
        val requiresConfirmation = GuestAccountSwitchPolicy.shouldConfirmAccountSwitch(
            isAnonymous = false,
            customerCount = 0,
            hasFinancialActivity = false,
        )
        assertFalse(requiresConfirmation)
    }
}