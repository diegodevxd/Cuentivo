package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Out-of-order replies from Play.
 *
 * Testing [EntitlementReconciler] alone was not enough: it always received the right
 * `sessionUserId`, because the test handed it one. The real defect was upstream — the gateway kept
 * the asking account in a field that the next query overwrote, so a slow reply for Ana got resolved
 * against Beto and arrived as Beto/Beto, which the reconciler happily accepts.
 *
 * These tests model the correlation itself: two requests alive at once, answering in the wrong
 * order, exactly as Play does.
 */
class BillingRequestTest {

    private val ana = "uid-ana"
    private val beto = "uid-beto"

    @Test
    fun `a late reply for the previous account is never credited to the new one`() {
        // Ana's query starts. Beto's query starts before Ana's answers. Then Ana's answer lands,
        // with Beto signed in. This is the exact sequence that was broken.
        val anaRequest = BillingRequest(ana)
        val betoRequest = BillingRequest(beto)

        val late = anaRequest.reply(SubscriptionTier.PRO, currentUserId = beto)

        assertEquals(SubscriptionTier.FREE, late.publish)
        assertFalse(late.persist)
        assertNull(late.userId)
        // And Beto's own answer is unaffected by Ana's having been in flight.
        assertEquals(SubscriptionTier.FREE, betoRequest.reply(SubscriptionTier.FREE, beto).publish)
    }

    @Test
    fun `overlapping requests each keep the account they started with`() {
        val anaRequest = BillingRequest(ana)
        val betoRequest = BillingRequest(beto)

        // Answered in reverse order of starting; each still resolves against its own account.
        val betoAnswer = betoRequest.reply(SubscriptionTier.PRO, currentUserId = beto)
        val anaAnswer = anaRequest.reply(SubscriptionTier.PRO, currentUserId = ana)

        assertEquals(beto, betoAnswer.userId)
        assertEquals(ana, anaAnswer.userId)
        assertTrue(betoAnswer.persist)
        assertTrue(anaAnswer.persist)
    }

    @Test
    fun `a reply for the session that is still current is accepted`() {
        val decision = BillingRequest(ana).reply(SubscriptionTier.PRO, currentUserId = ana)

        assertEquals(SubscriptionTier.PRO, decision.publish)
        assertTrue(decision.persist)
        assertEquals(ana, decision.userId)
    }

    @Test
    fun `a reply from a query started before anyone signed in is dropped`() {
        // Cold start: the app asks Play before Firebase Auth has restored a session. The answer
        // cannot belong to whoever signs in a moment later.
        val decision = BillingRequest(null).reply(SubscriptionTier.PRO, currentUserId = ana)

        assertEquals(SubscriptionTier.FREE, decision.publish)
        assertFalse(decision.persist)
    }

    @Test
    fun `a reply arriving after sign-out is dropped`() {
        val decision = BillingRequest(ana).reply(SubscriptionTier.PRO, currentUserId = null)

        assertEquals(SubscriptionTier.FREE, decision.publish)
        assertFalse(decision.persist)
    }

    @Test
    fun `a purchase credited to the account that opened the sheet survives a later sign-in`() {
        // Play's purchase callback is global. Ana buys; Beto signs in before Play calls back. The
        // purchase belongs to Ana, so with Beto current it must grant nothing rather than grant Beto.
        val purchase = BillingRequest(ana)

        assertEquals(SubscriptionTier.FREE, purchase.reply(SubscriptionTier.PRO, beto).publish)
        assertEquals(SubscriptionTier.PRO, purchase.reply(SubscriptionTier.PRO, ana).publish)
    }
}
