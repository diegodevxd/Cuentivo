package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The race these rules exist for: Play answers on its own schedule, Firebase Auth changes on
 * another. Every case here is one where the naive version — trusting whatever Play last said —
 * either showed Pro that the sync gate would refuse, or filed one account's purchase under
 * another account's uid.
 */
class EntitlementReconcilerTest {

    private val ana = "uid-ana"
    private val beto = "uid-beto"

    @Test
    fun `pro reported before anyone signs in is not granted`() {
        // The confirmed bug: on a cold start Billing answers PRO while currentUser is still null.
        // The UI used to go Pro while the sync gate, which reads the uid, stayed on FREE.
        val decision = EntitlementReconciler.resolve(SubscriptionTier.PRO, sessionUserId = null, currentUserId = null)

        assertEquals(SubscriptionTier.FREE, decision.publish)
        assertFalse(decision.persist)
        assertNull(decision.userId)
    }

    @Test
    fun `pro answered for the session that asked is granted and stored`() {
        val decision = EntitlementReconciler.resolve(SubscriptionTier.PRO, sessionUserId = ana, currentUserId = ana)

        assertEquals(SubscriptionTier.PRO, decision.publish)
        assertTrue(decision.persist)
        assertEquals(ana, decision.userId)
    }

    @Test
    fun `asking again after signing in is what finally grants pro`() {
        // The first answer arrives with nobody signed in and is dropped; the re-query after sign-in
        // carries the uid and is the one that counts. This is why signing in must re-reconcile.
        val beforeSignIn = EntitlementReconciler.resolve(SubscriptionTier.PRO, null, null)
        val afterSignIn = EntitlementReconciler.resolve(SubscriptionTier.PRO, ana, ana)

        assertEquals(SubscriptionTier.FREE, beforeSignIn.publish)
        assertEquals(SubscriptionTier.PRO, afterSignIn.publish)
    }

    @Test
    fun `a late answer belonging to a previous account never grants pro to the new one`() {
        // Ana was Pro and signed out; Beto signed in; Ana's reply lands now. Handing it to Beto is
        // how one person's subscription would unlock another person's ledger.
        val decision = EntitlementReconciler.resolve(SubscriptionTier.PRO, sessionUserId = ana, currentUserId = beto)

        assertEquals(SubscriptionTier.FREE, decision.publish)
        assertFalse(decision.persist)
    }

    @Test
    fun `an answer that arrives after signing out is dropped`() {
        val decision = EntitlementReconciler.resolve(SubscriptionTier.PRO, sessionUserId = ana, currentUserId = null)

        assertEquals(SubscriptionTier.FREE, decision.publish)
        assertFalse(decision.persist)
    }

    @Test
    fun `a downgrade for the right session is published and stored`() {
        // Downgrades must persist too, or the stored PRO would outlive the subscription.
        val decision = EntitlementReconciler.resolve(SubscriptionTier.FREE, ana, ana)

        assertEquals(SubscriptionTier.FREE, decision.publish)
        assertTrue(decision.persist)
        assertEquals(ana, decision.userId)
    }

    @Test
    fun `a blank uid counts as nobody`() {
        assertEquals(SubscriptionTier.FREE, EntitlementReconciler.resolve(SubscriptionTier.PRO, "", "").publish)
    }
}
