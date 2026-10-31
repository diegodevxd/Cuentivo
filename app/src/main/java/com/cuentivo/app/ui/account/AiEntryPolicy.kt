package com.cuentivo.app.ui.account

import com.cuentivo.app.domain.SubscriptionTier

sealed interface AiEntryRoute {
    /** The user does not have the CuentivoAI tier; show the subscription paywall. */
    data object Paywall : AiEntryRoute

    /** The user is subscribed to CuentivoAI and the assistant transport is available. */
    data object Assistant : AiEntryRoute

    /** The user is subscribed to CuentivoAI, but the assistant is not yet available in this build. */
    data object UnavailableInCurrentBuild : AiEntryRoute

    /** The user is subscribed to CuentivoAI, but the remote AI service is temporarily disabled. */
    data object ServiceDisabled : AiEntryRoute
}

object AiEntryPolicy {
    fun route(
        tier: SubscriptionTier,
        hasTransport: Boolean,
        isServiceAvailable: Boolean = true,
    ): AiEntryRoute {
        return when {
            tier != SubscriptionTier.AI -> AiEntryRoute.Paywall
            !hasTransport -> AiEntryRoute.UnavailableInCurrentBuild
            !isServiceAvailable -> AiEntryRoute.ServiceDisabled
            else -> AiEntryRoute.Assistant
        }
    }
}
