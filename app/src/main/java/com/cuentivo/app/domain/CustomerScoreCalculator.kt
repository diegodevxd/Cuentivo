package com.cuentivo.app.domain

/**
 * One sale as it looks to the score, stripped of Room. [allocatedCents] already excludes canceled
 * payments, and [lastPaymentDateEpochDay] is the date of the newest payment allocated to it.
 */
data class CustomerSaleHistory(
    val dueDateEpochDay: Long?,
    val totalCents: Long,
    val allocatedCents: Long,
    val lastPaymentDateEpochDay: Long?,
)

/** How a customer pays, in the four states worth painting a different color. */
enum class PaymentReliability {
    /** Pays on the agreed date, or close enough that it never hurt. */
    PUNCTUAL,

    /** Pays, but late enough that the owner has to chase them. */
    SLOW,

    /** Late by a lot, or sitting on a debt nobody has touched in months. */
    DELINQUENT,

    /** Not enough sales with an agreed date to say anything honest yet. */
    NO_HISTORY,
}

/** A customer's payment behavior, derived. Never stored — a payment changes it on the next read. */
data class CustomerScore(
    val reliability: PaymentReliability,
    val averageDaysLate: Int,
    val worstDaysLate: Int,
    val ratedSaleCount: Int,
)

/**
 * Answers the one question a credit business actually runs on: *should I let this person owe me?*
 *
 * The app already shows what everyone owes. What it never showed is whether they tend to pay, and
 * that has been sitting in the ledger the whole time. Nothing here is stored: like
 * [SaleStatusCalculator] and the balance itself, the score is derived on every read, so settling a
 * debt improves it immediately and no row anywhere can go stale.
 *
 * Pure integer arithmetic and no Android, like the rest of this layer. It returns an enum and never
 * a sentence — the UI resolves the wording, which is what keeps this testable on the JVM.
 */
object CustomerScoreCalculator {

    /**
     * Below this, the answer is "we do not know yet" rather than a color.
     *
     * Same reasoning as [CreditLimitAdvisor.suggest] withholding a suggestion on a single strike: a
     * false reading of *trustworthy* is the one mistake here that costs the owner real money, and
     * two sales paid on time is not evidence of anything.
     */
    private const val MIN_RATED_SALES = 3

    /** A couple of days past the date is how normal people pay, not a warning sign. */
    private const val PUNCTUAL_MAX_AVERAGE_DAYS = 3

    private const val SLOW_MAX_AVERAGE_DAYS = 20

    /** An untouched debt this old outranks any average. */
    private const val ABANDONED_DEBT_DAYS = 60

    fun score(sales: List<CustomerSaleHistory>, todayEpochDay: Long): CustomerScore {
        // A sale with no agreed date cannot be late, so it cannot be evidence either way. Counting
        // one as punctual would hand out green for free.
        val rated = sales.mapNotNull { sale ->
            val dueDate = sale.dueDateEpochDay ?: return@mapNotNull null
            val late = daysLate(sale, dueDate, todayEpochDay) ?: return@mapNotNull null
            RatedSale(daysLate = late, isOpen = sale.isOpen)
        }

        if (rated.size < MIN_RATED_SALES) {
            return CustomerScore(PaymentReliability.NO_HISTORY, 0, 0, rated.size)
        }

        val average = (rated.sumOf { it.daysLate } / rated.size).toInt()
        val worst = rated.maxOf { it.daysLate }.toInt()

        // Checked before the average so a long history of good behavior cannot bury a debt that has
        // been standing still for two months. That debt is the news. It has to be the *same* sale
        // that is both open and ancient: an old sale eventually paid late is history, not a debt.
        val reliability = when {
            rated.any { it.isOpen && it.daysLate > ABANDONED_DEBT_DAYS } -> PaymentReliability.DELINQUENT
            average <= PUNCTUAL_MAX_AVERAGE_DAYS -> PaymentReliability.PUNCTUAL
            average <= SLOW_MAX_AVERAGE_DAYS -> PaymentReliability.SLOW
            else -> PaymentReliability.DELINQUENT
        }

        return CustomerScore(reliability, average, worst, rated.size)
    }

    /**
     * How late one sale was, or still is.
     *
     * For a settled sale the newest allocated payment *is* the one that settled it, so its date is
     * the exact settlement day rather than an approximation. An open sale is measured against today
     * and keeps growing, which is what makes an abandoned debt look worse every week instead of
     * freezing at whatever it was when it went unpaid.
     *
     * Paying early counts as zero, never as negative: arriving a week ahead does not buy the right
     * to be a week late on the next one.
     */
    private fun daysLate(sale: CustomerSaleHistory, dueDateEpochDay: Long, todayEpochDay: Long): Long? {
        val settlementDay = if (sale.isOpen) todayEpochDay else sale.lastPaymentDateEpochDay
        // A settled sale with no payment date is a sale worth nothing, paid by nobody. It says
        // nothing about the customer, so it is left out rather than scored as punctual.
            ?: return null
        return maxOf(0L, settlementDay - dueDateEpochDay)
    }

    private val CustomerSaleHistory.isOpen: Boolean
        get() = allocatedCents < totalCents

    /** One sale that could be scored, and whether its clock is still running. */
    private data class RatedSale(val daysLate: Long, val isOpen: Boolean)
}
