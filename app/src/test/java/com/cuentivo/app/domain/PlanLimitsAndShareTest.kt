package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanLimitsAndShareTest {

    // Written against the constant rather than a hard-coded 5: tuning the free plan is meant to be a
    // one-line change in FeatureAccessManager, and a test repeating the number defeats that.
    @Test
    fun `free plan allows customers up to the limit`() {
        val lastFreeSlot = FeatureAccessManager.FREE_CUSTOMER_LIMIT - 1
        val decision = FeatureAccessManager.canAddCustomer(SubscriptionTier.FREE, activeCustomers = lastFreeSlot)
        assertTrue(decision is AccessDecision.Allowed)
    }

    @Test
    fun `free plan blocks the customer past the limit and says why`() {
        val decision = FeatureAccessManager.canAddCustomer(
            SubscriptionTier.FREE,
            activeCustomers = FeatureAccessManager.FREE_CUSTOMER_LIMIT,
        )
        assertTrue(decision is AccessDecision.Blocked)
        assertTrue((decision as AccessDecision.Blocked).detail.contains("Pro"))
        // The number offered has to be the number enforced, or the upgrade prompt lies.
        assertTrue(decision.detail.contains("${FeatureAccessManager.FREE_CUSTOMER_LIMIT}"))
    }

    @Test
    fun `the free customer limit is five`() {
        assertEquals(5, FeatureAccessManager.FREE_CUSTOMER_LIMIT)
    }

    @Test
    fun `pro plan has no customer or sale limit`() {
        assertTrue(FeatureAccessManager.canAddCustomer(SubscriptionTier.PRO, 5_000) is AccessDecision.Allowed)
        assertTrue(FeatureAccessManager.canRegisterSale(SubscriptionTier.PRO, 90_000) is AccessDecision.Allowed)
    }

    @Test
    fun `free plan blocks the sale past the limit`() {
        assertTrue(FeatureAccessManager.canRegisterSale(SubscriptionTier.FREE, 30) is AccessDecision.Blocked)
        assertTrue(FeatureAccessManager.canRegisterSale(SubscriptionTier.FREE, 29) is AccessDecision.Allowed)
    }

    @Test
    fun `paid features are closed on free and open on pro`() {
        assertTrue(FeatureAccessManager.canUse(SubscriptionTier.FREE, PaidFeature.CLOUD_BACKUP) is AccessDecision.Blocked)
        assertTrue(FeatureAccessManager.canUse(SubscriptionTier.PRO, PaidFeature.CLOUD_BACKUP) is AccessDecision.Allowed)
    }

    @Test
    fun `registering a payment is never blocked`() {
        assertTrue(FeatureAccessManager.canRegisterPayment() is AccessDecision.Allowed)
    }

    @Test
    fun `an unknown stored plan degrades to free rather than unlocking`() {
        assertEquals(SubscriptionTier.FREE, SubscriptionTier.fromStoredValue("PLATINUM"))
        assertEquals(SubscriptionTier.FREE, SubscriptionTier.fromStoredValue(null))
        assertEquals(SubscriptionTier.PRO, SubscriptionTier.fromStoredValue("PRO"))
    }

    @Test
    fun `business list shows every debtor largest first with the total`() {
        val text = DebtorShareText.forBusiness(
            "Tienda Ejemplo",
            listOf(Debtor("Ana", 15_000), Debtor("Beto", 40_000, isOverdue = true)),
        )
        assertTrue(text.indexOf("Beto") < text.indexOf("Ana"))
        assertTrue(text.contains("(vencido)"))
        assertTrue(text.contains("Total por cobrar"))
    }

    @Test
    fun `business list leaves out customers who owe nothing`() {
        val text = DebtorShareText.forBusiness("Tienda", listOf(Debtor("Ana", 0), Debtor("Beto", 100)))
        assertFalse(text.contains("Ana"))
    }

    @Test
    fun `customer message never mentions another customer`() {
        val text = DebtorShareText.forCustomer("Tienda", "Ana", 15_000)
        assertTrue(text.contains("Ana"))
        assertFalse(text.contains("Beto"))
        assertTrue(text.contains("Saldo pendiente"))
    }

    @Test
    fun `a settled customer is thanked instead of being asked for money`() {
        val text = DebtorShareText.forCustomer("Tienda", "Ana", 0)
        assertTrue(text.contains("al corriente"))
        assertFalse(text.contains("Saldo pendiente"))
    }

    @Test
    fun `customer message can carry that customer's own movements`() {
        val text = DebtorShareText.forCustomer(
            "Tienda",
            "Ana",
            5_000,
            listOf(
                StatementEntry("2026-07-01", "CHARGE", "DEBIT", 15_000, null, saleId = "s1", detail = "2 Jabón Zote"),
                StatementEntry("2026-07-10", "PAYMENT", "CREDIT", 10_000, null),
            ),
        )
        assertTrue(text.contains("Venta"))
        assertTrue(text.contains("Abono"))
        // What was bought, and a date a person can read: the message lands on WhatsApp, where
        // "2026-07-01  Compra" tells the customer nothing about their own debt.
        assertTrue(text.contains("2 Jabón Zote"))
        assertTrue(text.contains("01/07/2026"))
        assertFalse(text.contains("2026-07-01"))
    }

    @Test
    fun `money formats as pesos with two decimals`() {
        assertTrue(Money.format(150_50).contains("150.50"))
    }

    @Test
    fun `money refuses a third decimal instead of truncating it`() {
        assertEquals(null, Money.centsFromPesos("10.005"))
        assertEquals(1_000L, Money.centsFromPesos("10"))
        assertEquals(null, Money.positiveCentsFromPesos("0"))
    }
}
