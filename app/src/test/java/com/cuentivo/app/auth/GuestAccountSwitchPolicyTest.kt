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

    @Test
    fun collision_with_local_guest_data_resolves_to_confirm_before_sign_in() {
        val resolution = GuestAccountSwitchPolicy.resolveCollisionAction(
            isAnonymous = true,
            customerCount = 3,
            hasFinancialActivity = false,
        )
        org.junit.Assert.assertEquals(AccountCollisionResolution.CONFIRM_BEFORE_SIGN_IN, resolution)
    }

    @Test
    fun collision_without_local_guest_data_resolves_to_direct_sign_in() {
        val resolution = GuestAccountSwitchPolicy.resolveCollisionAction(
            isAnonymous = true,
            customerCount = 0,
            hasFinancialActivity = false,
        )
        org.junit.Assert.assertEquals(AccountCollisionResolution.DIRECT_SIGN_IN, resolution)
    }

    @Test
    fun collision_on_normal_account_resolves_to_direct_sign_in() {
        val resolution = GuestAccountSwitchPolicy.resolveCollisionAction(
            isAnonymous = false,
            customerCount = 10,
            hasFinancialActivity = true,
        )
        org.junit.Assert.assertEquals(AccountCollisionResolution.DIRECT_SIGN_IN, resolution)
    }

    @Test
    fun decideAccountSwitch_with_local_data_returns_confirm_with_email() {
        val decision = GuestAccountSwitchPolicy.decideAccountSwitch(
            isAnonymous = true,
            customerCount = 4,
            hasFinancialActivity = false,
            targetEmail = "diego@cuentivo.com",
        )
        org.junit.Assert.assertEquals(
            AccountSwitchDecision.Confirm(targetEmail = "diego@cuentivo.com"),
            decision,
        )
    }

    @Test
    fun decideAccountSwitch_without_local_data_returns_direct() {
        val decision = GuestAccountSwitchPolicy.decideAccountSwitch(
            isAnonymous = true,
            customerCount = 0,
            hasFinancialActivity = false,
            targetEmail = "diego@cuentivo.com",
        )
        org.junit.Assert.assertEquals(AccountSwitchDecision.Direct, decision)
    }

    @Test
    fun decideAccountSwitch_on_normal_account_returns_direct() {
        val decision = GuestAccountSwitchPolicy.decideAccountSwitch(
            isAnonymous = false,
            customerCount = 10,
            hasFinancialActivity = true,
            targetEmail = "diego@cuentivo.com",
        )
        org.junit.Assert.assertEquals(AccountSwitchDecision.Direct, decision)
    }
}