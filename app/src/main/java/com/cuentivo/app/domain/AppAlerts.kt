package com.cuentivo.app.domain

/** A dated sale that still owes money, stripped of Room so these rules stay testable. */
data class DueSale(
    val saleId: String,
    val customerId: String,
    val customerName: String,
    val dueDateEpochDay: Long,
    val outstandingCents: Long,
)

/** Something worth telling the owner about, with whose it is so the screen can lead there. */
data class Alert(
    val title: String,
    val detail: String,
    val severity: Severity,
    val customerId: String? = null,
) {
    enum class Severity { OVERDUE, DUE_SOON, INFO }
}

/**
 * What the app knows that the owner would want to be told.
 *
 * Computed on the spot from rows already in Room — no notifications table, no migration, no server.
 * That also means an alert can never go stale: pay the sale and it stops existing on the next read.
 *
 * Only the near future is worth interrupting someone about. A sale due in three weeks is not news,
 * and showing it would bury the two that are actually late.
 */
object AppAlerts {

    /** Beyond tomorrow it is a plan, not an alert. */
    private const val HORIZON_DAYS = 1L

    fun from(
        sales: List<DueSale>,
        todayEpochDay: Long,
        tier: SubscriptionTier,
        activeCustomers: Int,
    ): List<Alert> {
        val dated = sales
            .mapNotNull { sale -> alertFor(sale, todayEpochDay) }
            // Late first, and among those the one that has been waiting longest.
            .sortedWith(compareBy({ it.first.ordinal }, { it.second }))
            .map { it.third }

        return dated + planAlert(tier, activeCustomers)
    }

    private fun alertFor(sale: DueSale, todayEpochDay: Long): Triple<Alert.Severity, Long, Alert>? {
        val daysLate = todayEpochDay - sale.dueDateEpochDay
        val amount = Money.format(sale.outstandingCents)
        return when {
            daysLate > 0 -> Triple(
                Alert.Severity.OVERDUE,
                -daysLate,
                Alert(
                    title = sale.customerName,
                    detail = "Debe $amount desde hace ${plural(daysLate, "día", "días")}.",
                    severity = Alert.Severity.OVERDUE,
                    customerId = sale.customerId,
                ),
            )
            daysLate == 0L -> Triple(
                Alert.Severity.DUE_SOON,
                0L,
                Alert(
                    title = sale.customerName,
                    detail = "Le toca pagar hoy: $amount.",
                    severity = Alert.Severity.DUE_SOON,
                    customerId = sale.customerId,
                ),
            )
            -daysLate <= HORIZON_DAYS -> Triple(
                Alert.Severity.DUE_SOON,
                1L,
                Alert(
                    title = sale.customerName,
                    detail = "Le toca pagar mañana: $amount.",
                    severity = Alert.Severity.DUE_SOON,
                    customerId = sale.customerId,
                ),
            )
            else -> null
        }
    }

    /**
     * Warns before the wall, not at it.
     *
     * Finding out the limit exists while standing in front of a customer, halfway through adding
     * them, is the worst possible moment to learn about it.
     */
    private fun planAlert(tier: SubscriptionTier, activeCustomers: Int): List<Alert> {
        if (tier != SubscriptionTier.FREE) return emptyList()
        val remaining = FeatureAccessManager.FREE_CUSTOMER_LIMIT - activeCustomers
        return when {
            remaining <= 0 -> listOf(
                Alert(
                    title = "Llegaste al límite del plan gratis",
                    detail = "Para agregar más clientes necesitas Cuentivo Pro.",
                    severity = Alert.Severity.INFO,
                )
            )
            remaining == 1 -> listOf(
                Alert(
                    title = "Te queda 1 cliente",
                    detail = "El plan gratis permite ${FeatureAccessManager.FREE_CUSTOMER_LIMIT} clientes.",
                    severity = Alert.Severity.INFO,
                )
            )
            else -> emptyList()
        }
    }

    private fun plural(count: Long, singular: String, plural: String): String =
        if (count == 1L) "$count $singular" else "$count $plural"
}
