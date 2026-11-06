package com.cuentivo.app.auth

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.Test

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
        assertTrue(requiresConfirmation, "Un invitado con clientes debe recibir confirmación antes de cambiar de cuenta")
    }

    @Test
    fun guest_with_financial_activity_requires_confirmation() {
        val requiresConfirmation = GuestAccountSwitchPolicy.shouldConfirmAccountSwitch(
            isAnonymous = true,
            customerCount = 0,
            hasFinancialActivity = true,
        )
        assertTrue(requiresConfirmation, "Un invitado con actividad financiera debe recibir confirmación")
    }

    @Test
    fun guest_without_local_data_does_not_require_confirmation() {
        val requiresConfirmation = GuestAccountSwitchPolicy.shouldConfirmAccountSwitch(
            isAnonymous = true,
            customerCount = 0,
            hasFinancialActivity = false,
        )
        assertFalse(requiresConfirmation, "Un invitado sin datos no necesita advertencia")
    }

    @Test
    fun authenticated_account_with_data_does_not_require_guest_confirmation() {
        val requiresConfirmation = GuestAccountSwitchPolicy.shouldConfirmAccountSwitch(
            isAnonymous = false,
            customerCount = 10,
            hasFinancialActivity = true,
        )
        assertFalse(requiresConfirmation, "Una cuenta registrada normal no aplica para advertencia de invitado")
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
        assertEquals(AccountCollisionResolution.CONFIRM_BEFORE_SIGN_IN, resolution)
    }

    @Test
    fun collision_without_local_guest_data_resolves_to_direct_sign_in() {
        val resolution = GuestAccountSwitchPolicy.resolveCollisionAction(
            isAnonymous = true,
            customerCount = 0,
            hasFinancialActivity = false,
        )
        assertEquals(AccountCollisionResolution.DIRECT_SIGN_IN, resolution)
    }

    @Test
    fun collision_on_normal_account_resolves_to_direct_sign_in() {
        val resolution = GuestAccountSwitchPolicy.resolveCollisionAction(
            isAnonymous = false,
            customerCount = 10,
            hasFinancialActivity = true,
        )
        assertEquals(AccountCollisionResolution.DIRECT_SIGN_IN, resolution)
    }

    @Test
    fun decideAccountSwitch_with_local_data_returns_confirm_with_email() {
        val decision = GuestAccountSwitchPolicy.decideAccountSwitch(
            isAnonymous = true,
            customerCount = 4,
            hasFinancialActivity = false,
            targetEmail = "diego@cuentivo.com",
        )
        assertEquals(
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
        assertEquals(AccountSwitchDecision.Direct, decision)
    }

    @Test
    fun decideAccountSwitch_on_normal_account_returns_direct() {
        val decision = GuestAccountSwitchPolicy.decideAccountSwitch(
            isAnonymous = false,
            customerCount = 10,
            hasFinancialActivity = true,
            targetEmail = "diego@cuentivo.com",
        )
        assertEquals(AccountSwitchDecision.Direct, decision)
    }
}