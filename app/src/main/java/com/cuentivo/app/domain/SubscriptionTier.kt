package com.cuentivo.app.domain

/**
 * What the business is paying for. Three plans: a free tier meant for trying the app with real
 * customers, Pro, and CuentivoAI.
 *
 * **The plans nest, they do not stack.** [AI] is not an add-on bought on top of [PRO]; it is a
 * higher plan that includes everything Pro has plus the assistant. Nobody pays for two at once.
 * That is why code asks [includesProBenefits] rather than comparing against [PRO]: an `== PRO`
 * check would take cloud backup or photos away from the customer paying the most, silently.
 *
 * **Both facts are declared, never inferred.** [rank] is the commercial ordering, not [ordinal]:
 * reordering the constants for readability must not change who gets what. And
 * [includesProBenefits] is stated per plan rather than derived as "anything that is not FREE", so a
 * future TRIAL, INTERNAL or LEGACY tier starts closed and has to be opened on purpose.
 *
 * Every limit lives here and nowhere else. Tightening the free plan must be a one-line change in
 * this file, never a hunt through Composables.
 *
 * @param rank where this plan sits commercially; higher wins when someone holds two subscriptions.
 * @param includesProBenefits whether this plan carries everything Pro has.
 */
enum class SubscriptionTier(
    val rank: Int,
    val includesProBenefits: Boolean,
) {
    FREE(rank = 0, includesProBenefits = false),
    PRO(rank = 10, includesProBenefits = true),
    AI(rank = 20, includesProBenefits = true),
    ;

    companion object {
        /** Play Console product id for the Pro subscription. */
        const val PRO_PRODUCT_ID = "cuentivo_pro_mensual"

        /** Play Console product id for the CuentivoAI subscription. */
        const val AI_PRODUCT_ID = "cuentivo_ai_mensual"

        /** Falls back to FREE on anything unrecognized: an unknown plan must never unlock features. */
        fun fromStoredValue(raw: String?): SubscriptionTier =
            entries.firstOrNull { it.name == raw } ?: FREE
    }
}

/** A capability that the free plan does not include. */
enum class PaidFeature(val explanation: String) {
    CLOUD_BACKUP("Respaldo y sincronización en la nube"),
    PHOTOS("Fotos de productos y comprobantes de pago"),
    SPREADSHEET("Importar y exportar productos en Excel"),
    SHARE_FULL_LIST("Compartir la lista completa de deudores"),
    TEAM("Varios usuarios con roles"),
    AUDIT_LOG("Historial de auditoría"),
}

/** The answer to "can they do this?", with the reason ready to show when the answer is no. */
sealed interface AccessDecision {
    data object Allowed : AccessDecision
    data class Blocked(val title: String, val detail: String) : AccessDecision
}

/**
 * The single place that decides what the free plan may do.
 *
 * Client-side gating only: it decides what the app offers, not what the server accepts. That is fine
 * for plan limits, which protect revenue rather than other people's data — unlike [Permissions],
 * which mirrors rules the server enforces.
 */
object FeatureAccessManager {
    const val FREE_CUSTOMER_LIMIT = 5
    const val FREE_SALE_LIMIT = 30

    private const val UPGRADE = "Actualiza a Cuentivo Pro para quitar los límites."

    fun canAddCustomer(tier: SubscriptionTier, activeCustomers: Int): AccessDecision =
        if (tier.includesProBenefits || activeCustomers < FREE_CUSTOMER_LIMIT) {
            AccessDecision.Allowed
        } else {
            AccessDecision.Blocked(
                title = "Llegaste a $FREE_CUSTOMER_LIMIT clientes",
                detail = "El plan gratis permite $FREE_CUSTOMER_LIMIT clientes activos. $UPGRADE",
            )
        }

    fun canRegisterSale(tier: SubscriptionTier, salesRecorded: Int): AccessDecision =
        if (tier.includesProBenefits || salesRecorded < FREE_SALE_LIMIT) {
            AccessDecision.Allowed
        } else {
            AccessDecision.Blocked(
                title = "Llegaste a $FREE_SALE_LIMIT ventas",
                detail = "El plan gratis permite $FREE_SALE_LIMIT ventas registradas. $UPGRADE",
            )
        }

    fun canUse(tier: SubscriptionTier, feature: PaidFeature): AccessDecision =
        if (tier.includesProBenefits) {
            AccessDecision.Allowed
        } else {
            AccessDecision.Blocked(
                title = feature.explanation,
                detail = "Esta función es parte de Cuentivo Pro. $UPGRADE",
            )
        }

    /**
     * Registering a payment is never blocked, on any plan.
     *
     * Someone who hit the free limit still has customers who owe them money; refusing to record a
     * payment would corrupt their ledger to sell a subscription.
     */
    fun canRegisterPayment(): AccessDecision = AccessDecision.Allowed
}
