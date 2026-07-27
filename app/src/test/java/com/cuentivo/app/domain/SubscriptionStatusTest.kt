package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionStatusTest {

    private fun purchase(
        productId: String = SubscriptionTier.PRO_PRODUCT_ID,
        isPurchased: Boolean = true,
        isAcknowledged: Boolean = true,
    ) = PlayPurchase(listOf(productId), isPurchased, isAcknowledged)

    @Test
    fun `no purchases means free`() {
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(emptyList()))
    }

    @Test
    fun `a completed purchase of the pro product unlocks pro`() {
        assertEquals(SubscriptionTier.PRO, SubscriptionStatus.tierFrom(listOf(purchase())))
    }

    @Test
    fun `a pending purchase does not unlock pro`() {
        // Cash payment methods leave a purchase pending for hours. Granting Pro here would hand the
        // paid plan to anyone willing to start a payment and never finish it.
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(purchase(isPurchased = false))))
    }

    @Test
    fun `a purchase of some other product does not unlock pro`() {
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(purchase(productId = "otra_cosa"))))
    }

    @Test
    fun `access is granted before the purchase is acknowledged`() {
        // Google's order is deliver first, acknowledge second; waiting would show the free plan to
        // someone who just paid.
        assertEquals(SubscriptionTier.PRO, SubscriptionStatus.tierFrom(listOf(purchase(isAcknowledged = false))))
    }

    @Test
    fun `only unacknowledged completed pro purchases need acknowledging`() {
        val needsIt = purchase(isAcknowledged = false)
        val purchases = listOf(
            needsIt,
            purchase(),
            purchase(isPurchased = false, isAcknowledged = false),
            purchase(productId = "otra_cosa", isAcknowledged = false),
        )

        assertEquals(listOf(needsIt), SubscriptionStatus.needsAcknowledgement(purchases))
    }

    @Test
    fun `the product id matches the one configured in play console`() {
        // A typo here is invisible until a real purchase silently fails to unlock anything.
        assertEquals("cuentivo_pro_mensual", SubscriptionTier.PRO_PRODUCT_ID)
    }
}
