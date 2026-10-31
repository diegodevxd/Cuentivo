package com.cuentivo.app.billing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.billingclient.api.Purchase
import com.cuentivo.app.domain.BillingAccountHasher
import com.cuentivo.app.domain.SubscriptionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillingGatewayMappingTest {

    @Test
    fun toPlayPurchase_faithfully_extracts_obfuscatedAccountId_from_google_play_purchase() {
        val userHash = BillingAccountHasher.obfuscate("user_diego_123")!!
        val json = """
            {
                "orderId": "GPA.1234-5678-9012-34567",
                "packageName": "com.cuentivo.app",
                "productId": "cuentivo_ai_mensual",
                "productIds": ["cuentivo_ai_mensual"],
                "purchaseTime": 1724800000000,
                "purchaseState": 1,
                "purchaseToken": "token_abc_xyz",
                "acknowledged": true,
                "obfuscatedAccountId": "$userHash"
            }
        """.trimIndent()

        val purchase = Purchase(json, "dummy_signature")
        val playPurchase = purchase.toPlayPurchase()

        assertEquals(listOf(SubscriptionTier.AI_PRODUCT_ID), playPurchase.productIds)
        assertEquals(true, playPurchase.isPurchased)
        assertEquals(true, playPurchase.isAcknowledged)
        assertEquals(userHash, playPurchase.obfuscatedAccountId)
    }

    @Test
    fun toPlayPurchase_handles_legacy_purchase_without_accountIdentifiers() {
        val json = """
            {
                "orderId": "GPA.1234-5678-9012-34567",
                "packageName": "com.cuentivo.app",
                "productId": "cuentivo_pro_mensual",
                "productIds": ["cuentivo_pro_mensual"],
                "purchaseTime": 1724800000000,
                "purchaseState": 1,
                "purchaseToken": "token_legacy_pro",
                "acknowledged": true
            }
        """.trimIndent()

        val purchase = Purchase(json, "dummy_signature")
        val playPurchase = purchase.toPlayPurchase()

        assertEquals(listOf(SubscriptionTier.PRO_PRODUCT_ID), playPurchase.productIds)
        assertEquals(true, playPurchase.isPurchased)
        assertEquals(true, playPurchase.isAcknowledged)
        assertNull(playPurchase.obfuscatedAccountId)
    }

    @Test
    fun toPlayPurchase_normalizes_empty_string_obfuscatedAccountId_to_null() {
        // Play returns obfuscatedAccountId = "" when no account identifier was set
        val json = """
            {
                "orderId": "GPA.1234-5678-9012-34567",
                "packageName": "com.cuentivo.app",
                "productId": "cuentivo_pro_mensual",
                "productIds": ["cuentivo_pro_mensual"],
                "purchaseTime": 1724800000000,
                "purchaseState": 1,
                "purchaseToken": "token_legacy_pro_empty",
                "acknowledged": true,
                "obfuscatedAccountId": ""
            }
        """.trimIndent()

        val purchase = Purchase(json, "dummy_signature")
        val playPurchase = purchase.toPlayPurchase()

        assertEquals(listOf(SubscriptionTier.PRO_PRODUCT_ID), playPurchase.productIds)
        assertEquals(true, playPurchase.isPurchased)
        assertEquals(true, playPurchase.isAcknowledged)
        assertNull(playPurchase.obfuscatedAccountId)
    }
}
