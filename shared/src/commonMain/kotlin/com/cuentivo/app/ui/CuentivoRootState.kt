package com.cuentivo.app.ui

import androidx.compose.runtime.mutableStateOf
import com.cuentivo.app.domain.SubscriptionTier

/** Lo que la plataforma le avisa a la raíz desde afuera de la composición. */
class CuentivoRootState {
    /**
     * The live plan, owned by the Activity because Play answers asynchronously and can change it at
     * any moment — a purchase made on another device shows up on the next refresh.
     */
    val tier = mutableStateOf(SubscriptionTier.FREE)

    /**
     * A reminder notification was tapped and the navigation is still owed. Held as a state so the
     * host can consume it once through CuentivoApp; the intent's action itself is nulled at read
     * time so a rotation cannot re-fire the navigation.
     */
    val openOverdueCustomers = mutableStateOf(false)

    /**
     * Messages raised while the Account sheet is open (e.g. the notification permission being
     * denied). Separate from the branch-local [uiMessage] because the permission callback is
     * declared outside that branch.
     */
    val accountMessage = mutableStateOf<String?>(null)

    /**
     * Pro and AI prices as Play formats them, null until Play answers. Held here rather than read on demand
     * because the query is asynchronous and finishes after the composition that wants it.
     */
    val proPrice = mutableStateOf<String?>(null)
    val aiPrice = mutableStateOf<String?>(null)
    val proTrialMonths = mutableStateOf(0)
    val aiTrialMonths = mutableStateOf(0)

    /**
     * Whether Play has answered yet for the current account, which FREE alone cannot express.
     *
     * [tier] starts at FREE and is also set to FREE the instant an account changes, so "free
     * plan" and "we have not heard back" look identical. On a device with data that distinction does
     * not matter; on an empty one it decides whether the person is offered a brand-new business or
     * made to wait for the one they already own. Getting that wrong is how a reinstall ends up with
     * a second business and an app that looks empty.
     */
    val entitlementResolved = mutableStateOf(false)
    val billingMessage = mutableStateOf<String?>(null)
    val requireAccount = mutableStateOf(false)
}
