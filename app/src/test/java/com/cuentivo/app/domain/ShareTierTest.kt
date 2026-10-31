package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTierTest {

    @Test
    fun debug_switch_on_forces_free_so_the_footer_shows() {
        val shared = ShareTier.resolve(SubscriptionTier.PRO, isDebugBuild = true, forceFree = true)
        assertEquals(SubscriptionTier.FREE, shared)
        assertTrue(DebtorShareText.forCustomer(shared, "Abarrotes Don Luis", "Sra. Mendoza", 59_900)
            .contains("Enviado con Cuentivo"))
    }

    @Test
    fun release_build_ignores_the_switch() {
        for (tier in SubscriptionTier.entries) {
            assertEquals(tier, ShareTier.resolve(tier, isDebugBuild = false, forceFree = true))
        }
        val shared = ShareTier.resolve(SubscriptionTier.AI, isDebugBuild = false, forceFree = true)
        assertFalse(DebtorShareText.forCustomer(shared, "Abarrotes Don Luis", "Sra. Mendoza", 59_900)
            .contains("Enviado con Cuentivo"))
    }

    @Test
    fun switch_off_keeps_the_real_plan() {
        for (tier in SubscriptionTier.entries) {
            assertEquals(tier, ShareTier.resolve(tier, isDebugBuild = true, forceFree = false))
        }
    }
}
