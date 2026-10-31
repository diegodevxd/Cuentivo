package com.cuentivo.app.ai

import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.domain.CustomerNameNormalizer
import java.time.LocalDate
import java.util.Collections
import kotlinx.coroutines.CancellationException

/**
 * One row in a debtors query answer. Display data and debt amount only; no internal entity state.
 */
data class AiDebtorRow(
    val customerId: String,
    val customerName: String,
    val balanceCents: Long,
)

/**
 * A safe candidate for query clarification. Local id and display name only, no financial data.
 */
data class AiQueryCustomerCandidate(
    val customerId: String,
    val displayName: String,
)

/**
 * Deterministic block reasons for business queries.
 */
enum class AiQueryBlockReason {
    INVALID_DATE,
    START_AFTER_END,
    PERIOD_TOO_LONG,
}

/**
 * Outcome of resolving a validated business query proposal against the local ledger.
 */
sealed interface AiBusinessQueryResult {

    sealed interface Answer : AiBusinessQueryResult {

        data class CustomerBalance(
            val customerId: String,
            val customerName: String,
            val balanceCents: Long,
        ) : Answer

        class CustomersWithDebt private constructor(
            rawRows: List<AiDebtorRow>,
            val totalCents: Long,
        ) : Answer {
            val rows: List<AiDebtorRow> = Collections.unmodifiableList(rawRows.toList())

            companion object {
                operator fun invoke(rows: List<AiDebtorRow>, totalCents: Long): CustomersWithDebt =
                    CustomersWithDebt(rawRows = rows, totalCents = totalCents)
            }

            override fun equals(other: Any?): Boolean =
                this === other ||
                    (other is CustomersWithDebt &&
                        rows == other.rows &&
                        totalCents == other.totalCents)

            override fun hashCode(): Int = 31 * rows.hashCode() + totalCents.hashCode()

            override fun toString(): String = "CustomersWithDebt(rows=$rows, totalCents=$totalCents)"
        }

        data class SalesForPeriod(
            val startEpochDay: Long,
            val endEpochDay: Long,
            val soldCents: Long,
            val collectedCents: Long,
        ) : Answer
    }

    class CustomerSelectionRequired private constructor(
        rawCandidates: List<AiQueryCustomerCandidate>,
    ) : AiBusinessQueryResult {
        val candidates: List<AiQueryCustomerCandidate> =
            Collections.unmodifiableList(rawCandidates.toList())

        companion object {
            operator fun invoke(candidates: List<AiQueryCustomerCandidate>): CustomerSelectionRequired =
                CustomerSelectionRequired(rawCandidates = candidates)
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is CustomerSelectionRequired && candidates == other.candidates)

        override fun hashCode(): Int = candidates.hashCode()

        override fun toString(): String = "CustomerSelectionRequired(candidates=$candidates)"
    }

    data class CustomerNotFound(val queriedName: String) : AiBusinessQueryResult

    data class Blocked(val reason: AiQueryBlockReason) : AiBusinessQueryResult

    data object Unavailable : AiBusinessQueryResult
}

/**
 * Pure, read-only resolver that executes business queries against deterministic domain seams.
 * It never writes to Room, Firestore, sync queue or audit logs.
 */
class AiBusinessQueryResolver(
    private val searchCustomers: suspend (businessId: String, query: String) -> List<CustomerEntity>,
    private val customerBalance: suspend (businessId: String, customerId: String) -> Long,
    private val customersWithBalances: suspend (businessId: String) -> List<CustomerBalanceRow>,
    private val salesTotal: suspend (businessId: String, startEpochDay: Long, endEpochDay: Long) -> Long,
    private val collectedTotal: suspend (businessId: String, startEpochDay: Long, endEpochDay: Long) -> Long,
) {

    suspend fun resolve(
        businessId: String,
        proposal: BusinessQueryProposal,
        choice: AiEntityChoice? = null,
    ): AiBusinessQueryResult {
        if (businessId.isBlank()) {
            return AiBusinessQueryResult.Unavailable
        }
        return try {
            resolveInternal(businessId, proposal, choice)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AiBusinessQueryResult.Unavailable
        }
    }

    private suspend fun resolveInternal(
        businessId: String,
        proposal: BusinessQueryProposal,
        choice: AiEntityChoice?,
    ): AiBusinessQueryResult = when (proposal.queryType) {
        BusinessQueryType.CUSTOMER_BALANCE -> resolveCustomerBalance(businessId, proposal, choice)
        BusinessQueryType.CUSTOMERS_WITH_DEBT -> resolveCustomersWithDebt(businessId)
        BusinessQueryType.SALES_FOR_PERIOD -> resolveSalesForPeriod(businessId, proposal)
    }

    private suspend fun resolveCustomerBalance(
        businessId: String,
        proposal: BusinessQueryProposal,
        choice: AiEntityChoice?,
    ): AiBusinessQueryResult {
        val customerName = proposal.customerName
        if (customerName.isNullOrBlank()) {
            return AiBusinessQueryResult.Unavailable
        }

        val candidates = searchCustomers(businessId, customerName)
            .filter { it.businessId == businessId && it.isActive && it.deletedAtEpochMillis == null }

        if (choice != null) {
            return when (choice) {
                is AiEntityChoice.UseExisting -> {
                    val matching = candidates.firstOrNull { it.id == choice.entityId }
                        ?: return AiBusinessQueryResult.Unavailable
                    val balance = customerBalance(businessId, matching.id)
                    AiBusinessQueryResult.Answer.CustomerBalance(
                        customerId = matching.id,
                        customerName = matching.name,
                        balanceCents = balance,
                    )
                }
                AiEntityChoice.CreateNew -> {
                    // Queries never create new customers
                    AiBusinessQueryResult.Unavailable
                }
            }
        }

        val normalizedQuery = CustomerNameNormalizer.key(customerName)
        val exactMatches = candidates.filter { it.normalizedName == normalizedQuery }
        return when (exactMatches.size) {
            1 -> {
                val matched = exactMatches.first()
                val balance = customerBalance(businessId, matched.id)
                AiBusinessQueryResult.Answer.CustomerBalance(
                    customerId = matched.id,
                    customerName = matched.name,
                    balanceCents = balance,
                )
            }
            0 -> {
                if (candidates.isEmpty()) {
                    AiBusinessQueryResult.CustomerNotFound(customerName)
                } else {
                    AiBusinessQueryResult.CustomerSelectionRequired(
                        candidates.map { AiQueryCustomerCandidate(it.id, it.name) },
                    )
                }
            }
            else -> {
                AiBusinessQueryResult.CustomerSelectionRequired(
                    candidates.map { AiQueryCustomerCandidate(it.id, it.name) },
                )
            }
        }
    }

    private suspend fun resolveCustomersWithDebt(
        businessId: String,
    ): AiBusinessQueryResult {
        val rows = customersWithBalances(businessId)
            .filter {
                it.customer.businessId == businessId &&
                    it.customer.isActive &&
                    it.customer.deletedAtEpochMillis == null
            }
        val positiveRows = rows.filter { it.balanceCents > 0L }
        val totalCents = positiveRows.fold(0L) { acc, row -> Math.addExact(acc, row.balanceCents) }
        val top20 = positiveRows.take(20).map {
            AiDebtorRow(
                customerId = it.customer.id,
                customerName = it.customer.name,
                balanceCents = it.balanceCents,
            )
        }
        return AiBusinessQueryResult.Answer.CustomersWithDebt(
            rows = top20,
            totalCents = totalCents,
        )
    }

    private suspend fun resolveSalesForPeriod(
        businessId: String,
        proposal: BusinessQueryProposal,
    ): AiBusinessQueryResult {
        val period = proposal.period ?: return AiBusinessQueryResult.Unavailable

        val startEpochDay = parseEpochDay(period.startYear, period.startMonth, period.startDay)
            ?: return AiBusinessQueryResult.Blocked(AiQueryBlockReason.INVALID_DATE)

        val endEpochDay = parseEpochDay(period.endYear, period.endMonth, period.endDay)
            ?: return AiBusinessQueryResult.Blocked(AiQueryBlockReason.INVALID_DATE)

        if (startEpochDay > endEpochDay) {
            return AiBusinessQueryResult.Blocked(AiQueryBlockReason.START_AFTER_END)
        }

        val daysCount = try {
            Math.addExact(Math.subtractExact(endEpochDay, startEpochDay), 1L)
        } catch (_: ArithmeticException) {
            return AiBusinessQueryResult.Blocked(AiQueryBlockReason.PERIOD_TOO_LONG)
        }

        if (daysCount > 366L) {
            return AiBusinessQueryResult.Blocked(AiQueryBlockReason.PERIOD_TOO_LONG)
        }

        val soldCents = salesTotal(businessId, startEpochDay, endEpochDay)
        val collectedCents = collectedTotal(businessId, startEpochDay, endEpochDay)

        return AiBusinessQueryResult.Answer.SalesForPeriod(
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay,
            soldCents = soldCents,
            collectedCents = collectedCents,
        )
    }

    private fun parseEpochDay(year: Int, month: Int, day: Int): Long? = try {
        LocalDate.of(year, month, day).toEpochDay()
    } catch (_: Exception) {
        null
    }
}
