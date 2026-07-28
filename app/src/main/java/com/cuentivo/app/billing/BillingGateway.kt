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
import com.cuentivo.app.domain.BillingRequest
import com.cuentivo.app.domain.PlayPurchase
import com.cuentivo.app.domain.SubscriptionStatus
import com.cuentivo.app.domain.SubscriptionTier

/**
 * The app's only conversation with Google Play Billing.
 *
 * Deciding *what a purchase means* lives in [SubscriptionStatus] and
 * [com.cuentivo.app.domain.EntitlementReconciler], which are pure and tested. This class does only
 * the parts that need Play: connecting, asking, launching the purchase sheet and acknowledging.
 *
 * Three things it is careful about:
 *
 * - **Every reply carries the account that asked for it.** Play answers out of order, so a
 *   [BillingRequest] is created when a query starts and captured in that query's closure. Reading a
 *   shared field instead is how a slow answer for Ana ends up credited to Beto, who signed in
 *   meanwhile. There is no shared field left to read for that purpose.
 * - **Acknowledgement.** Play refunds any purchase not acknowledged within three days. Every path
 *   that sees a purchase runs it through the acknowledge step, including the one on startup, so a
 *   purchase completed while the app was closed still gets confirmed.
 * - **Verification is on-device only.** Whatever Play tells this phone is taken at face value, which
 *   a determined person can fake. That is a deliberate trade: hardening it means a server checking
 *   the purchase token, which means the Blaze plan. It protects revenue, not other people's data.
 */
class BillingGateway(
    context: Context,
    private val onTierChanged: (SubscriptionTier, String?) -> Unit,
    private val onMessage: (String) -> Unit,
) {
    private var proProduct: ProductDetails? = null

    /**
     * What to query once the connection finishes, and which account opened the purchase sheet.
     *
     * These are the only mutable pieces left, and neither identifies a *reply*: an in-flight query
     * already holds its own [BillingRequest] in its closure. The first exists because a connection
     * completes later than the call that triggered it; the second because Play's purchase callback
     * is global rather than attached to the flow that opened it.
     */
    @Volatile private var connectionRequest: BillingRequest = BillingRequest(null)
    @Volatile private var purchaseRequest: BillingRequest = BillingRequest(null)

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        // Attributed to the account that opened the sheet, not to whoever is signed in when Play
        // gets back to us — those differ if the person signed out mid-purchase.
        val request = purchaseRequest
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> applyPurchases(purchases.orEmpty(), request)
            // Backing out of the payment sheet is a normal thing to do, not an error to report.
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh(request.userId)
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
     * @param userId the account this query is made on behalf of; it comes back with the answer.
     */
    fun start(userId: String?) {
        if (client.isReady) {
            refresh(userId)
            return
        }
        // Remembered so the query that follows a slow connection still belongs to an account we
        // asked for, even if another start() happened while the socket was opening.
        connectionRequest = BillingRequest(userId)
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    loadProduct()
                    refresh(connectionRequest.userId)
                }
                // A failure here stays silent on purpose: someone without Play services must still
                // be able to use the app, just on the free plan.
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    /**
     * Re-reads what Play believes this account owns.
     *
     * The startup source of truth for the tier, what must run again after a sign-in so the answer
     * can finally be attributed to somebody, and what keeps a subscriber's stored confirmation from
     * going stale.
     */
    fun refresh(userId: String?) {
        // Local and immutable. This is the fix: the closure below closes over *this* value, so two
        // overlapping queries cannot be confused no matter what order Play answers in.
        val request = BillingRequest(userId)
        if (!client.isReady) {
            connectionRequest = request
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                applyPurchases(purchases, request)
            }
        }
    }

    /**
     * Opens Google's payment sheet. Needs the real Activity; an application context will not do.
     *
     * @param userId the account buying. Play's purchase callback is global, so without this a
     *   purchase could be credited to whoever signed in after the sheet opened.
     */
    fun launchPurchase(activity: Activity, userId: String?) {
        purchaseRequest = BillingRequest(userId)
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

    /**
     * @param request the query this answer belongs to. Passed in rather than read from a field, so a
     *   late reply can never be mistaken for a newer one.
     */
    private fun applyPurchases(purchases: List<Purchase>, request: BillingRequest) {
        val reported = purchases.map { purchase ->
            PlayPurchase(
                productIds = purchase.products,
                isPurchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED,
                isAcknowledged = purchase.isAcknowledged,
            )
        }
        onTierChanged(SubscriptionStatus.tierFrom(reported), request.userId)

        // Acknowledgement is about the purchase itself, not about who it is credited to, so it runs
        // regardless of whether the reply was attributable — the three-day refund clock does not
        // care which account happens to be signed in.
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
