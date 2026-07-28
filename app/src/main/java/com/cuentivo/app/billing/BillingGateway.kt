package com.cuentivo.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.cuentivo.app.domain.PlayPurchase
import com.cuentivo.app.domain.SubscriptionStatus
import com.cuentivo.app.domain.SubscriptionTier

/**
 * The app's only conversation with Google Play Billing.
 *
 * Deciding *what a purchase means* lives in [SubscriptionStatus], which is pure and tested. This
 * class does only the parts that need Play: connecting, asking, launching the purchase sheet and
 * acknowledging.
 *
 * Two things it is careful about:
 *
 * - **Acknowledgement.** Play refunds any purchase not acknowledged within three days. Every path
 *   that sees a purchase runs it through [acknowledgeIfNeeded], including the one on startup, so a
 *   purchase that completed while the app was closed still gets confirmed.
 * - **Verification is on-device only.** Whatever Play tells this phone is taken at face value, which
 *   a determined person can fake. That is a deliberate trade for now: hardening it means a server
 *   checking the purchase token, which means the Blaze plan. It protects revenue, not other
 *   people's data, so the cost of being wrong is a free subscription — not a leak.
 */
class BillingGateway(
    context: Context,
    private val onTierChanged: (SubscriptionTier, String?) -> Unit,
    private val onMessage: (String) -> Unit,
) {
    private var proProduct: ProductDetails? = null

    /**
     * Who was signed in when the current query was started.
     *
     * Play answers whenever it feels like it, and Firebase Auth changes independently: a reply can
     * land after a sign-out, or arrive on a cold start before anyone has signed in at all. Carrying
     * the asking account back with the answer is what lets the caller discard a reply that no longer
     * belongs to anybody — attributing it to whoever is signed in *now* is how one person's
     * subscription would end up unlocking another person's data.
     */
    @Volatile private var sessionUserId: String? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> applyPurchases(purchases.orEmpty())
            // Backing out of the payment sheet is a normal thing to do, not an error to report.
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> onMessage("No se pudo completar la compra. Inténtalo de nuevo.")
        }
    }

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    /**
     * Connects and reconciles what this device already owns. Safe to call more than once.
     *
     * @param userId the account this query is being made on behalf of; comes back with the answer.
     */
    fun start(userId: String?) {
        sessionUserId = userId
        if (client.isReady) {
            refresh(userId)
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    loadProduct()
                    refresh()
                }
                // A failure here stays silent on purpose: someone without Play services must still
                // be able to use the app, just on the free plan.
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    /**
     * Re-reads what Play believes this account owns. The startup source of truth for the tier, and
     * what must run again after a sign-in so the answer can finally be attributed to somebody.
     */
    fun refresh(userId: String? = sessionUserId) {
        sessionUserId = userId
        if (!client.isReady) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) applyPurchases(purchases)
        }
    }

    /** Opens Google's payment sheet. Needs the real Activity; an application context will not do. */
    fun launchPurchase(activity: Activity) {
        val product = proProduct
        if (!client.isReady || product == null) {
            onMessage("Las suscripciones no están disponibles en este momento")
            return
        }
        // A subscription is bought through one of its offers, so the base plan's token is required.
        val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            onMessage("El plan no está disponible todavía")
            return
        }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        client.launchBillingFlow(activity, flow)
    }

    fun close() {
        if (client.isReady) client.endConnection()
    }

    private fun loadProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SubscriptionTier.PRO_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                proProduct = details.productDetailsList.firstOrNull()
            }
        }
    }

    private fun applyPurchases(purchases: List<Purchase>) {
        val reported = purchases.map { purchase ->
            PlayPurchase(
                productIds = purchase.products,
                isPurchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED,
                isAcknowledged = purchase.isAcknowledged,
            )
        }
        // The session that asked travels with the answer; the caller decides whether it still counts.
        onTierChanged(SubscriptionStatus.tierFrom(reported), sessionUserId)

        val pending = SubscriptionStatus.needsAcknowledgement(reported).toSet()
        purchases.forEachIndexed { index, purchase ->
            if (reported[index] in pending) acknowledge(purchase)
        }
    }

    private fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { /* A failure is retried by the next refresh(). */ }
    }
}
