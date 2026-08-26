package com.cuentivo.app.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import com.cuentivo.app.domain.BillingAccountHasher
import com.cuentivo.app.domain.BillingReconnectPolicy
import com.cuentivo.app.domain.BillingRequest
import com.cuentivo.app.domain.PlayPurchase
import com.cuentivo.app.domain.SubscriptionReplacementPolicy
import com.cuentivo.app.domain.SubscriptionStatus
import com.cuentivo.app.domain.SubscriptionTier
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's only conversation with Google Play Billing.
 *
 * Deciding *what a purchase means* lives in [SubscriptionStatus] and
 * [com.cuentivo.app.domain.EntitlementReconciler], which are pure and tested. This class does only
 * the parts that need Play: connecting, asking, launching the purchase sheet and acknowledging.
 *
 * Core architectural safeguards:
 *
 * - **Two products, two prices.** Queries both [SubscriptionTier.PRO_PRODUCT_ID] and
 *   [SubscriptionTier.AI_PRODUCT_ID] simultaneously and reports their localized formatted prices
 *   via [onPriceLoaded]. Prices come exclusively from Google Play, never hardcoded.
 * - **Plan replacement, never double subscription.** When changing plans (Pro -> AI or AI -> Pro),
 *   [launchPurchase] attaches [BillingFlowParams.SubscriptionUpdateParams] with the old purchase
 *   token and the designated proration policy ([SubscriptionReplacementPolicy]). No user can ever
 *   end up with two active subscriptions simultaneously.
 * - **Stable obfuscated account identifier.** Every purchase flow sets `setObfuscatedAccountId`
 *   derived via SHA-256 hash of the Firebase UID ([BillingAccountHasher]), ensuring determinism
 *   and privacy without exposing raw UIDs to Play.
 * - **Purchase token capture for server validation.** Completed purchase tokens are captured and
 *   dispatched to [onPurchaseTokenReceived] for T3 backend validation. In accordance with Project
 *   Rule 4, purchase tokens are NEVER logged or forwarded to Analytics/Crashlytics.
 * - **Acknowledgement.** Play refunds any purchase not acknowledged within three days. Every path
 *   that sees a purchase runs it through the acknowledge step for both Pro and AI.
 * - **Android tier is a UI signal only.** Client-side [SubscriptionTier.AI] updates the UI, but
 *   actual access to AI capabilities is granted solely by backend validation (T3).
 */
class BillingGateway(
    context: Context,
    private val onTierChanged: (SubscriptionTier, String?) -> Unit,
    private val onMessage: (String) -> Unit,
    /**
     * Formatted price loaded from Play per product ID ([SubscriptionTier.PRO_PRODUCT_ID],
     * [SubscriptionTier.AI_PRODUCT_ID]), or null while not loaded.
     */
    private val onPriceLoaded: (productId: String, formattedPrice: String?) -> Unit = { _, _ -> },
    /**
     * Dispatches captured purchase tokens for backend verification.
     * **Project Rule 4:** NEVER log or expose this token.
     */
    private val onPurchaseTokenReceived: (token: String, productId: String, userId: String?) -> Unit = { _, _, _ -> },
) {
    private val products = ConcurrentHashMap<String, ProductDetails>()
    @Volatile private var activePurchases: List<Purchase> = emptyList()

    /**
     * What to query once the connection finishes, and which account opened the purchase sheet.
     */
    @Volatile private var connectionRequest: BillingRequest = BillingRequest(null)
    @Volatile private var purchaseRequest: BillingRequest = BillingRequest(null)

    /** Reconnection bookkeeping. See [BillingReconnectPolicy] for why any of it exists. */
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var reconnectAttempt = 0
    @Volatile private var isConnecting = false
    @Volatile private var isClosed = false

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
        reconnectAttempt = 0
        connect(userId)
    }

    private fun connect(userId: String?) {
        if (isClosed || isConnecting) return
        isConnecting = true
        connectionRequest = BillingRequest(userId)
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                isConnecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    reconnectAttempt = 0
                    loadProduct()
                    refresh(connectionRequest.userId)
                } else {
                    scheduleReconnect(connectionRequest.userId)
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnecting = false
                scheduleReconnect(connectionRequest.userId)
            }
        })
    }

    private fun scheduleReconnect(userId: String?) {
        if (isClosed) return
        val delay = BillingReconnectPolicy.delayMillisForAttempt(reconnectAttempt + 1) ?: return
        reconnectAttempt += 1
        handler.postDelayed({ connect(userId) }, delay)
    }

    /**
     * Re-reads what Play believes this account owns.
     */
    fun refresh(userId: String?) {
        val request = BillingRequest(userId)
        if (!client.isReady) {
            connectionRequest = request
            start(userId)
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
     * Opens Google's payment sheet for [targetProductId].
     *
     * If the user already owns another subscription plan (e.g. Pro and upgrading to AI, or AI and
     * downgrading to Pro), this configures [BillingFlowParams.SubscriptionUpdateParams] with the
     * existing purchase token and proration mode to replace the subscription rather than creating
     * a duplicate billing charge.
     *
     * @param activity the foreground Activity.
     * @param userId the account buying, used to derive an obfuscated account identifier for Play.
     * @param targetProductId the product ID to purchase (defaults to [SubscriptionTier.PRO_PRODUCT_ID]).
     */
    fun launchPurchase(
        activity: Activity,
        userId: String?,
        targetProductId: String = SubscriptionTier.PRO_PRODUCT_ID,
    ) {
        purchaseRequest = BillingRequest(userId)
        val product = products[targetProductId]
        if (!client.isReady || product == null) {
            onMessage("Las suscripciones no están disponibles en este momento")
            return
        }
        val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            onMessage("El plan no está disponible todavía")
            return
        }
        val flowBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(offerToken)
                        .build()
                )
            )

        // Set stable obfuscated account identifier derived from the Firebase UID (T3 prerequisite).
        val obfuscatedAccountId = BillingAccountHasher.obfuscate(userId)
        if (obfuscatedAccountId != null) {
            flowBuilder.setObfuscatedAccountId(obfuscatedAccountId)
        }

        // Plan change replacement: map active purchases to PlayPurchase and consult domain policy.
        val reportedPurchases = activePurchases.map { purchase ->
            PlayPurchase(
                productIds = purchase.products,
                isPurchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED,
                isAcknowledged = purchase.isAcknowledged,
            )
        }
        val productToReplace = SubscriptionReplacementPolicy.findProductToReplace(
            activePurchases = reportedPurchases,
            targetProductId = targetProductId,
        )
        if (productToReplace != null) {
            val existingPurchase = activePurchases.firstOrNull {
                it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.contains(productToReplace)
            }
            if (existingPurchase != null) {
                val policyMode = SubscriptionReplacementPolicy.replacementModeFor(
                    currentProductId = productToReplace,
                    targetProductId = targetProductId,
                ) ?: com.cuentivo.app.domain.ReplacementMode.CHARGE_PRORATED

                val playReplacementMode = when (policyMode) {
                    com.cuentivo.app.domain.ReplacementMode.CHARGE_PRORATED ->
                        BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE
                    com.cuentivo.app.domain.ReplacementMode.DEFERRED ->
                        BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED
                }

                val updateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(existingPurchase.purchaseToken)
                    .setSubscriptionReplacementMode(playReplacementMode)
                    .build()
                flowBuilder.setSubscriptionUpdateParams(updateParams)
            }
        }

        client.launchBillingFlow(activity, flowBuilder.build())
    }

    /**
     * Convenience overload taking [SubscriptionTier].
     */
    fun launchPurchase(
        activity: Activity,
        userId: String?,
        targetTier: SubscriptionTier,
    ) {
        val productId = when (targetTier) {
            SubscriptionTier.AI -> SubscriptionTier.AI_PRODUCT_ID
            SubscriptionTier.PRO -> SubscriptionTier.PRO_PRODUCT_ID
            SubscriptionTier.FREE -> {
                onMessage("El plan gratuito ya está activo")
                return
            }
        }
        launchPurchase(activity = activity, userId = userId, targetProductId = productId)
    }

    /** Ends the connection and cancels any pending retry, so a closed gateway stays closed. */
    fun close() {
        isClosed = true
        handler.removeCallbacksAndMessages(null)
        if (client.isReady) client.endConnection()
    }

    private fun loadProduct() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SubscriptionTier.PRO_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SubscriptionTier.AI_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                details.productDetailsList.forEach { product ->
                    products[product.productId] = product
                    val formattedPrice = product
                        .subscriptionOfferDetails
                        ?.firstOrNull()
                        ?.pricingPhases
                        ?.pricingPhaseList
                        ?.firstOrNull()
                        ?.formattedPrice
                    onPriceLoaded(product.productId, formattedPrice)
                }
            }
        }
    }

    /**
     * @param request the query this answer belongs to. Passed in rather than read from a field, so a
     *   late reply can never be mistaken for a newer one.
     */
    private fun applyPurchases(purchases: List<Purchase>, request: BillingRequest) {
        activePurchases = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val reported = purchases.map { purchase ->
            PlayPurchase(
                productIds = purchase.products,
                isPurchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED,
                isAcknowledged = purchase.isAcknowledged,
            )
        }
        onTierChanged(SubscriptionStatus.tierFrom(reported), request.userId)

        // Capture purchase tokens for T3 backend validation.
        // Project Rule 4: NEVER log or expose purchase tokens.
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { purchase ->
            purchase.products.forEach { productId ->
                onPurchaseTokenReceived(purchase.purchaseToken, productId, request.userId)
            }
        }

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
