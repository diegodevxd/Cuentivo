package com.cuentivo.app.domain

/**
 * What the business is paying for. Two plans only: a free tier meant for trying the app with real
 * customers, and Pro.
 *
 * Every limit lives here and nowhere else. Tightening the free plan must be a one-line change in
 * this file, never a hunt through Composables.
 */
enum class SubscriptionTier {
    FREE,
    PRO,
    ;

    companion object {
        /** Play Console product id for the single paid subscription. */
        const val PRO_PRODUCT_ID = "cuentivo_pro_mensual"

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
    const val FREE_CUSTOMER_LIMIT = 10
    const val FREE_SALE_LIMIT = 30

    private const val UPGRADE = "Actualiza a Cuentivo Pro para quitar los límites."

    fun canAddCustomer(tier: SubscriptionTier, activeCustomers: Int): AccessDecision =
        if (tier == SubscriptionTier.PRO || activeCustomers < FREE_CUSTOMER_LIMIT) {
            AccessDecision.Allowed
        } else {
            AccessDecision.Blocked(
                title = "Llegaste a $FREE_CUSTOMER_LIMIT clientes",
                detail = "El plan gratis permite $FREE_CUSTOMER_LIMIT clientes activos. $UPGRADE",
            )
        }

    fun canRegisterSale(tier: SubscriptionTier, salesRecorded: Int): AccessDecision =
        if (tier == SubscriptionTier.PRO || salesRecorded < FREE_SALE_LIMIT) {
            AccessDecision.Allowed
        } else {
            AccessDecision.Blocked(
                title = "Llegaste a $FREE_SALE_LIMIT ventas",
                detail = "El plan gratis permite $FREE_SALE_LIMIT ventas registradas. $UPGRADE",
            )
        }

    fun canUse(tier: SubscriptionTier, feature: PaidFeature): AccessDecision =
        if (tier == SubscriptionTier.PRO) {
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
