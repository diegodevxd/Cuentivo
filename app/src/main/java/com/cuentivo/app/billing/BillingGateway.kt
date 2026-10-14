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
import com.cuentivo.app.domain.PurchaseBlockReason
import com.cuentivo.app.domain.PurchaseLaunchDecision
import com.cuentivo.app.domain.PurchaseLaunchPolicy
import com.cuentivo.app.domain.PurchasesQueryState
import com.cuentivo.app.domain.ReplacementMode
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
 * - **Zero race condition plan replacement.** [launchPurchase] queries purchases fresh on the spot
 *   before constructing the billing flow, never relying on stale or pending cache.
 * - **Deterministic proration and replacement.** Evaluated via pure [PurchaseLaunchPolicy].
 *   Upgrades use [ReplacementMode.CHARGE_PRORATED] and downgrades use [ReplacementMode.DEFERRED].
 * - **Fail-closed security.** Blocked states (user mismatch, pending query, already active plan,
 *   inconsistent state with both plans active, or missing user ID) immediately fail closed without
 *   opening a duplicate billing flow.
 * - **Stable obfuscated account identifier.** Every purchase flow sets `setObfuscatedAccountId`
 *   derived via SHA-256 hash of the Firebase UID.
 * - **Purchase token capture for server validation.** Completed purchase tokens are captured and
 *   dispatched to [onPurchaseTokenReceived] for T3 backend validation. In accordance with Project
 *   Rule 4, purchase tokens are NEVER logged or forwarded to Analytics/Crashlytics.
 * - **Acknowledgement.** Play refunds any purchase not acknowledged within three days. Every path
 *   that sees a purchase runs it through the acknowledge step for both Pro and AI.
 * - **Android tier is a UI signal only.** Client-side [SubscriptionTier.AI] updates the UI, but
 *   actual access to AI capabilities is granted solely by backend validation (T3).
 */
class BillingGateway(
    private val context: Context,
    private val onTierChanged: (SubscriptionTier, String?) -> Unit,
    private val onMessage: (String) -> Unit,
    /**
     * Formatted price loaded from Play per product ID ([SubscriptionTier.PRO_PRODUCT_ID],
     * [SubscriptionTier.AI_PRODUCT_ID]), or null while not loaded.
     */
    private val onPriceLoaded: (productId: String, formattedPrice: String?) -> Unit = { _, _ -> },
    /**
     * Whole free-trial months Play offers this user for a product (0 = none, or already used).
     * [onPriceLoaded] always carries the price *after* the trial.
     */
    private val onTrialLoaded: (productId: String, freeTrialMonths: Int) -> Unit = { _, _ -> },
    /**
     * Dispatches captured purchase tokens for backend verification.
     * **Project Rule 4:** NEVER log or expose this token.
     */
    private val onPurchaseTokenReceived: (token: String, productId: String, userId: String?) -> Unit = { _, _, _ -> },
    /**
     * Invoked when a purchase attempt is blocked because an authenticated persistent account is required.
     */
    private val onRequireAccount: () -> Unit = {},
) {
    private val products = ConcurrentHashMap<String, ProductDetails>()
    @Volatile private var purchasesQueryState: PurchasesQueryState = PurchasesQueryState.Pending

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
            else -> onMessage(context.getString(com.cuentivo.app.R.string.billing_purchase_failed))
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
        purchasesQueryState = PurchasesQueryState.Pending
        connectionRequest = BillingRequest(userId)
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                isConnecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    reconnectAttempt = 0
                    loadProduct()
                    refresh(connectionRequest.userId)
                } else {
                    purchasesQueryState = PurchasesQueryState.Failed
                    scheduleReconnect(connectionRequest.userId)
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnecting = false
                purchasesQueryState = PurchasesQueryState.Pending
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
            purchasesQueryState = PurchasesQueryState.Pending
            connectionRequest = request
            start(userId)
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val reported = purchases.map { it.toPlayPurchase() }
                purchasesQueryState = PurchasesQueryState.Resolved(
                    purchases = reported,
                    queriedUserId = request.userId,
                )
                applyPurchases(purchases, request)
            } else {
                purchasesQueryState = PurchasesQueryState.Failed
            }
        }
    }

    /**
     * Opens Google's payment sheet for [targetProductId].
     *
     * Queries active purchases in real-time to avoid stale race conditions, evaluates
     * [PurchaseLaunchPolicy], and attaches [BillingFlowParams.SubscriptionUpdateParams]
     * if upgrading or downgrading.
     *
     * @param activity the foreground Activity.
     * @param userId the account buying, used to derive an obfuscated account identifier for Play.
     * @param targetProductId the product ID to purchase (defaults to [SubscriptionTier.PRO_PRODUCT_ID]).
     */
    fun launchPurchase(
        activity: Activity,
        userId: String?,
        targetProductId: String = SubscriptionTier.PRO_PRODUCT_ID,
        isAnonymous: Boolean = false,
    ) {
        if (!client.isReady) {
            onMessage(context.getString(com.cuentivo.app.R.string.billing_subscriptions_unavailable))
            return
        }

        val product = products[targetProductId]
        if (product == null) {
            onMessage(context.getString(com.cuentivo.app.R.string.billing_subscriptions_unavailable))
            return
        }
        val offerToken = selectOffer(product)?.offerToken
        if (offerToken == null) {
            onMessage(context.getString(com.cuentivo.app.R.string.billing_plan_not_available))
            return
        }

        // Query purchases on the spot to guarantee atomic state without relying on stale cache.
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                purchasesQueryState = PurchasesQueryState.Failed
                onMessage(context.getString(com.cuentivo.app.R.string.billing_subscription_query_failed))
                return@queryPurchasesAsync
            }

            val reported = purchases.map { it.toPlayPurchase() }
            val queryState = PurchasesQueryState.Resolved(
                purchases = reported,
                queriedUserId = userId,
            )
            purchasesQueryState = queryState
            applyPurchases(purchases, BillingRequest(userId))

            val decision = PurchaseLaunchPolicy.evaluate(
                queryState = queryState,
                currentUserId = userId,
                targetProductId = targetProductId,
                isAnonymous = isAnonymous,
            )

            when (decision) {
                is PurchaseLaunchDecision.Proceed -> {
                    val flowBuilder = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(
                            listOf(
                                BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(product)
                                    .setOfferToken(offerToken)
                                    .build()
                            )
                        )
                        .setObfuscatedAccountId(decision.obfuscatedAccountId)

                    if (decision.productToReplace != null && decision.replacementMode != null) {
                        val existingPurchase = purchases.firstOrNull { purchase ->
                            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                                purchase.products.contains(decision.productToReplace)
                        }
                        if (existingPurchase == null) {
                            onMessage(context.getString(com.cuentivo.app.R.string.billing_replacement_not_identified))
                            return@queryPurchasesAsync
                        }

                        val playReplacementMode = when (decision.replacementMode) {
                            ReplacementMode.CHARGE_PRORATED ->
                                BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE
                            ReplacementMode.DEFERRED ->
                                BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED
                        }

                        val updateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                            .setOldPurchaseToken(existingPurchase.purchaseToken)
                            .setSubscriptionReplacementMode(playReplacementMode)
                            .build()
                        flowBuilder.setSubscriptionUpdateParams(updateParams)
                    }

                    purchaseRequest = BillingRequest(userId)
                    client.launchBillingFlow(activity, flowBuilder.build())
                }
                is PurchaseLaunchDecision.Blocked -> {
                    when (decision.reason) {
                        PurchaseBlockReason.ANONYMOUS_SESSION -> {
                            onMessage(context.getString(com.cuentivo.app.R.string.purchase_blocked_anonymous))
                            onRequireAccount()
                        }
                        PurchaseBlockReason.ALREADY_ACTIVE -> {
                            onMessage(context.getString(com.cuentivo.app.R.string.billing_plan_already_active))
                            refresh(userId)
                        }
                        PurchaseBlockReason.INCONSISTENT_STATE -> {
                            onMessage(context.getString(com.cuentivo.app.R.string.billing_plan_change_in_progress))
                            refresh(userId)
                        }
                        PurchaseBlockReason.MISSING_USER_ID -> {
                            onMessage(context.getString(com.cuentivo.app.R.string.billing_sign_in_required))
                        }
                        PurchaseBlockReason.USER_MISMATCH -> {
                            onMessage(context.getString(com.cuentivo.app.R.string.billing_session_changed))
                            refresh(userId)
                        }
                        PurchaseBlockReason.QUERY_PENDING, PurchaseBlockReason.QUERY_FAILED -> {
                            onMessage(context.getString(com.cuentivo.app.R.string.billing_subscription_query_failed))
                        }
                        PurchaseBlockReason.UNKNOWN_PRODUCT, PurchaseBlockReason.INVALID_REPLACEMENT -> {
                            onMessage(context.getString(com.cuentivo.app.R.string.billing_purchase_could_not_start))
                        }
                    }
                }
            }
        }
    }

    /**
     * Convenience overload taking [SubscriptionTier].
     */
    fun launchPurchase(
        activity: Activity,
        userId: String?,
        targetTier: SubscriptionTier,
        isAnonymous: Boolean = false,
    ) {
        val productId = when (targetTier) {
            SubscriptionTier.AI -> SubscriptionTier.AI_PRODUCT_ID
            SubscriptionTier.PRO -> SubscriptionTier.PRO_PRODUCT_ID
            SubscriptionTier.FREE -> {
                onMessage(context.getString(com.cuentivo.app.R.string.billing_free_plan_active))
                return
            }
        }
        launchPurchase(activity = activity, userId = userId, targetProductId = productId, isAnonymous = isAnonymous)
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
                    val offer = selectOffer(product)
                    onPriceLoaded(product.productId, offer?.recurringPrice)
                    onTrialLoaded(product.productId, offer?.freeTrialMonths ?: 0)
                }
            }
        }
    }

    private fun selectOffer(product: ProductDetails): SelectedOffer? =
        SubscriptionOfferSelector.select(
            product.subscriptionOfferDetails.orEmpty().map { details ->
                OfferCandidate(
                    offerToken = details.offerToken,
                    phases = details.pricingPhases.pricingPhaseList.map {
                        OfferPhase(it.priceAmountMicros, it.formattedPrice, it.billingPeriod)
                    },
                )
            },
        )

    /**
     * @param request the query this answer belongs to. Passed in rather than read from a field, so a
     *   late reply can never be mistaken for a newer one.
     */
    private fun applyPurchases(purchases: List<Purchase>, request: BillingRequest) {
        val reported = purchases.map { it.toPlayPurchase() }
        val userAccountHash = BillingAccountHasher.obfuscate(request.userId)
        onTierChanged(SubscriptionStatus.tierFrom(reported, userAccountHash), request.userId)

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
            if (reported[index] in pending) acknowledge(purchase, request.userId)
        }
    }

    private fun acknowledge(purchase: Purchase, userId: String?) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                // Refreshing pulls the now-acknowledged purchases so isAcknowledged flips to true.
                refresh(userId)
            }
        }
    }
}

/**
 * Maps a Google Play Billing [Purchase] object to our domain model [PlayPurchase], faithfully
 * propagating the [Purchase.getAccountIdentifiers] obfuscated account ID.
 *
 * Normalizes blank/empty strings to null at the system boundary: Google Play Billing returns an
 * empty string `""` (instead of null) for legacy purchases or purchases where no obfuscated account
 * was set. Normalizing here guarantees that domain rules evaluating legacy Pro purchases remain clean
 * and reliable.
 */
internal fun Purchase.toPlayPurchase(): PlayPurchase = PlayPurchase(
    productIds = products,
    isPurchased = purchaseState == Purchase.PurchaseState.PURCHASED,
    isAcknowledged = isAcknowledged,
    obfuscatedAccountId = accountIdentifiers?.obfuscatedAccountId?.takeIf { it.isNotBlank() },
)
