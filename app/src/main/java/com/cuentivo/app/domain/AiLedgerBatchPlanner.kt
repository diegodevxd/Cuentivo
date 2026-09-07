package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.FinancialRejectionReason
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation
import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.Collections
import java.util.UUID

/**
 * Diagnostic reason explaining why a reviewed ledger operation or batch was rejected during planning.
 */
sealed interface BatchPlanningRejection {
    data class ExceedsBatchCapacity(val maxAllowed: Int, val actualCount: Int) : BatchPlanningRejection
    data class MissingDate(val lineIndex: Int) : BatchPlanningRejection
    data class FinancialError(val lineIndex: Int, val reason: FinancialRejectionReason) : BatchPlanningRejection
    data class BlankBusinessOrUser(val message: String) : BatchPlanningRejection
}

sealed interface BatchPlanningResult {
    data class Success(val plan: PlannedLedgerBatch) : BatchPlanningResult
    data class Rejected(val rejections: List<BatchPlanningRejection>) : BatchPlanningResult
}

/**
 * Deterministically planned ledger batch ready for single atomic database transaction.
 */
data class PlannedLedgerBatch(
    val batchIdempotencyKey: String,
    val businessId: String,
    val sellerUserId: String,
    val nowEpochMillis: Long,
    val todayEpochDay: Long,
    val newCustomers: List<CustomerEntity>,
    val sales: List<SaleEntity>,
    val saleItems: List<SaleItemEntity>,
    val chargeMovements: List<AccountMovementEntity>,
    val payments: List<PaymentEntity>,
    val paymentMovements: List<AccountMovementEntity>,
    val paymentAllocations: List<PaymentAllocationEntity>,
    val skippedRowIndices: List<Int>,
    val executableOperationsCount: Int,
)

/**
 * Pure planning engine for ledger batch import (Fase B Paso 5).
 *
 * Rules:
 * - Up to 25 operations maximum.
 * - Deduplicates shared new customers deterministically across rows.
 * - Computes running outstanding debt per customer across previous Room debt and newly created batch sales.
 * - Allocates payments against outstanding debt in strict chronological order (dueDate, then saleDate, then saleId).
 * - Generates deterministic idempotency keys bound to businessId and approved executable content.
 * - Never calls Gemini, network, Room or system clocks (clock passed in as pure parameters).
 */
object AiLedgerBatchPlanner {
    const val MAX_BATCH_OPERATIONS = 25
    const val LINE_NAME_SALE = "Venta en libreta"
    const val LINE_NAME_PRIOR_DEBT = OpeningBalance.LINE_NAME

    /**
     * The key a batch will be reserved under. Derived from the business and from what the person
     * actually approved, so re-confirming the same page returns the first result instead of writing
     * it twice, and changing an amount produces a different batch.
     *
     * Public because the caller needs it BEFORE planning: once a page is written, planning it again
     * sees the debt it already changed and would reject a payment that was in fact recorded.
     */
    fun batchKeyFor(
        businessId: String,
        registrableOperations: List<LedgerOperation>,
        resolvedDatesByLineIndex: Map<Int, Long>,
    ): String {
        val contentDigest = registrableOperations.joinToString(separator = "|") { op ->
            val dateEpoch = resolvedDatesByLineIndex[op.lineIndex] ?: 0L
            val normName = CustomerNameNormalizer.key(op.customerName.orEmpty())
            "${op.lineIndex}:${op.proposedKind}:$normName:${op.amountCents}:${op.description.orEmpty()}:$dateEpoch"
        }
        return "ai-ledger-batch-" + UUID.nameUUIDFromBytes(
            "$businessId:$contentDigest".toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }

    /**
     * Resolves the effective epoch day for a row date candidate.
     *
     * Rules:
     * - An absent date (null candidate) takes [pageDateEpochDay] if [pageDateEpochDay] is valid (> 0 and <= [todayEpochDay]).
     * - An explicit date candidate MUST be valid calendar day and <= [todayEpochDay]. An invalid explicit candidate
     *   returns null and NEVER falls back to [pageDateEpochDay].
     */
    internal fun resolveDate(
        dateCandidate: SafeDateCandidate?,
        pageDateEpochDay: Long?,
        todayEpochDay: Long,
    ): Long? {
        if (dateCandidate == null) {
            return if (pageDateEpochDay != null && pageDateEpochDay > 0 && pageDateEpochDay <= todayEpochDay) {
                pageDateEpochDay
            } else {
                null
            }
        }
        val candidateEpoch = toEpochDay(dateCandidate) ?: return null
        if (candidateEpoch <= 0 || candidateEpoch > todayEpochDay) {
            return null
        }
        return candidateEpoch
    }

    /** The dates a page resolves to, so a caller can compute [batchKeyFor] without planning. */
    fun resolvedDatesFor(
        operations: List<LedgerOperation>,
        pageDateEpochDay: Long?,
        todayEpochDay: Long,
    ): Map<Int, Long> {
        val resolved = mutableMapOf<Int, Long>()
        for (op in operations.filter { isRegisterableLedgerOperation(it) }) {
            val effective = resolveDate(op.date, pageDateEpochDay, todayEpochDay)
            if (effective != null) {
                resolved[op.lineIndex] = effective
            }
        }
        return resolved
    }

    fun plan(
        businessId: String,
        userId: String,
        operations: List<LedgerOperation>,
        existingCustomerMap: Map<String, CustomerEntity>, // keyed by normalizedCustomerName
        existingOutstandingSalesByCustomerId: Map<String, List<OutstandingSale>>,
        pageDateEpochDay: Long?,
        todayEpochDay: Long,
        nowEpochMillis: Long,
    ): BatchPlanningResult {
        if (businessId.isBlank() || userId.isBlank()) {
            return BatchPlanningResult.Rejected(
                listOf(BatchPlanningRejection.BlankBusinessOrUser("Business ID and User ID must not be blank."))
            )
        }

        val rejections = mutableListOf<BatchPlanningRejection>()

        // 1. Filter registrable operations. Non-registrable rows are skipped.
        val registrableOps = mutableListOf<LedgerOperation>()
        val skippedRowIndices = mutableListOf<Int>()

        for (op in operations) {
            if (isRegisterableLedgerOperation(op)) {
                registrableOps.add(op)
            } else {
                skippedRowIndices.add(op.lineIndex)
            }
        }

        if (registrableOps.size > MAX_BATCH_OPERATIONS) {
            return BatchPlanningResult.Rejected(
                listOf(BatchPlanningRejection.ExceedsBatchCapacity(MAX_BATCH_OPERATIONS, registrableOps.size))
            )
        }

        // 2. Validate dates: each row must have a valid date <= todayEpochDay, or fallback to valid pageDateEpochDay
        val resolvedDatesByLineIndex = mutableMapOf<Int, Long>()
        for (op in registrableOps) {
            val effectiveDate = resolveDate(op.date, pageDateEpochDay, todayEpochDay)
            if (effectiveDate == null) {
                rejections.add(BatchPlanningRejection.MissingDate(op.lineIndex))
            } else {
                resolvedDatesByLineIndex[op.lineIndex] = effectiveDate
            }
        }

        if (rejections.isNotEmpty()) {
            return BatchPlanningResult.Rejected(rejections)
        }

        // 3. Group by customer normalized name to plan debt transitions and validate financials
        val groupedOps = LinkedHashMap<String, MutableList<LedgerOperation>>()
        for (op in registrableOps) {
            val key = com.cuentivo.app.domain.CustomerNameNormalizer.key(op.customerName.orEmpty())
            groupedOps.getOrPut(key) { mutableListOf() }.add(op)
        }

        // First pass: check for any financial errors across all groups
        for ((normKey, ops) in groupedOps) {
            val existingCust = existingCustomerMap[normKey]
            val existingSales = existingCust?.id?.let { existingOutstandingSalesByCustomerId[it] }.orEmpty()
            var runningBalance = existingSales.filter { it.outstandingCents > 0 }.sumOf { it.outstandingCents }

            for (op in ops) {
                val cents = op.amountCents?.toLongOrNull() ?: 0L
                if (cents <= 0L) {
                    rejections.add(BatchPlanningRejection.FinancialError(op.lineIndex, FinancialRejectionReason.ZERO_AMOUNT))
                    continue
                }
                when (op.proposedKind) {
                    ProposedLedgerKind.SALE, ProposedLedgerKind.PRIOR_DEBT -> {
                        try {
                            runningBalance = Math.addExact(runningBalance, cents)
                        } catch (_: ArithmeticException) {
                            rejections.add(BatchPlanningRejection.FinancialError(op.lineIndex, FinancialRejectionReason.OVERFLOW))
                        }
                    }
                    ProposedLedgerKind.PAYMENT -> {
                        if (runningBalance <= 0L) {
                            rejections.add(BatchPlanningRejection.FinancialError(op.lineIndex, FinancialRejectionReason.PAYMENT_WITHOUT_DEBT))
                        } else if (cents > runningBalance) {
                            rejections.add(BatchPlanningRejection.FinancialError(op.lineIndex, FinancialRejectionReason.PAYMENT_EXCEEDS_DEBT))
                        } else {
                            try {
                                runningBalance = Math.subtractExact(runningBalance, cents)
                            } catch (_: ArithmeticException) {
                                rejections.add(BatchPlanningRejection.FinancialError(op.lineIndex, FinancialRejectionReason.OVERFLOW))
                            }
                        }
                    }
                    null -> {}
                }
            }
        }

        if (rejections.isNotEmpty()) {
            return BatchPlanningResult.Rejected(rejections)
        }

        // 4. Deterministic key bound to the business and to the approved content.
        val batchKey = batchKeyFor(businessId, registrableOps, resolvedDatesByLineIndex)

        // 5. Build entities
        val newCustomers = mutableListOf<CustomerEntity>()
        val createdCustomerMap = mutableMapOf<String, CustomerEntity>()

        // Resolve customer entities (deduplicated by normalized name)
        for ((normKey, ops) in groupedOps) {
            val existing = existingCustomerMap[normKey]
            if (existing != null) {
                createdCustomerMap[normKey] = existing
            } else {
                val displayName = ops.firstOrNull { !it.customerName.isNullOrBlank() }?.customerName?.trim() ?: normKey
                val customerId = UUID.nameUUIDFromBytes(
                    "$batchKey:customer:$normKey".toByteArray(StandardCharsets.UTF_8)
                ).toString()
                val newCust = CustomerEntity(
                    id = customerId,
                    businessId = businessId,
                    name = displayName,
                    normalizedName = normKey,
                    phone = null,
                    email = null,
                    address = null,
                    notes = null,
                    creditLimitCents = 0L,
                    isActive = true,
                    createdAtEpochMillis = nowEpochMillis,
                    updatedAtEpochMillis = nowEpochMillis,
                    deletedAtEpochMillis = null,
                )
                newCustomers.add(newCust)
                createdCustomerMap[normKey] = newCust
            }
        }

        val sales = mutableListOf<SaleEntity>()
        val saleItems = mutableListOf<SaleItemEntity>()
        val chargeMovements = mutableListOf<AccountMovementEntity>()
        val payments = mutableListOf<PaymentEntity>()
        val paymentMovements = mutableListOf<AccountMovementEntity>()
        val paymentAllocations = mutableListOf<PaymentAllocationEntity>()

        // Track running outstanding sales per customer for multi-payment allocation
        val runningOutstandingSalesByCustomerId = mutableMapOf<String, MutableList<OutstandingSale>>()
        for ((normKey, cust) in createdCustomerMap) {
            val existing = existingOutstandingSalesByCustomerId[cust.id].orEmpty().map {
                OutstandingSale(it.id, it.outstandingCents, it.dueDateEpochDay, it.saleDateEpochDay)
            }.toMutableList()
            runningOutstandingSalesByCustomerId[cust.id] = existing
        }

        // Process operations in order
        for (op in registrableOps) {
            val normKey = com.cuentivo.app.domain.CustomerNameNormalizer.key(op.customerName.orEmpty())
            val customer = checkNotNull(createdCustomerMap[normKey]) { "Customer must be resolved" }
            val amountCents = checkNotNull(op.amountCents?.toLongOrNull())
            val effectiveDate = checkNotNull(resolvedDatesByLineIndex[op.lineIndex])
            val opKey = "$batchKey:${op.lineIndex}"

            when (op.proposedKind) {
                ProposedLedgerKind.SALE -> {
                    val saleId = SaleOperationIds.sale(opKey)
                    val chargeId = SaleOperationIds.chargeMovement(opKey)
                    val itemId = SaleOperationIds.uncatalogedSaleItem(opKey, 0)
                    val lineDescription = op.description?.trim().takeUnless { it.isNullOrBlank() } ?: LINE_NAME_SALE

                    val uncataloged = UncatalogedSaleBuilder.build(
                        saleId = saleId,
                        itemId = itemId,
                        chargeId = chargeId,
                        businessId = businessId,
                        customerId = customer.id,
                        sellerUserId = userId,
                        amountCents = amountCents,
                        saleNumber = "V-$nowEpochMillis-${op.lineIndex}",
                        saleDateEpochDay = effectiveDate,
                        todayEpochDay = todayEpochDay,
                        nowEpochMillis = nowEpochMillis,
                        dueDateEpochDay = null,
                        notes = null,
                        lineNameSnapshot = lineDescription,
                    )
                    sales.add(uncataloged.sale)
                    saleItems.add(uncataloged.item)
                    chargeMovements.add(uncataloged.charge)

                    // Add new sale to running outstanding sales for this customer
                    val runningList = runningOutstandingSalesByCustomerId.getOrPut(customer.id) { mutableListOf() }
                    runningList.add(OutstandingSale(saleId, amountCents, null, effectiveDate))
                }
                ProposedLedgerKind.PRIOR_DEBT -> {
                    val saleId = SaleOperationIds.sale(opKey)
                    val chargeId = SaleOperationIds.chargeMovement(opKey)
                    val itemId = SaleOperationIds.uncatalogedSaleItem(opKey, 0)
                    val lineDescription = op.description?.trim().takeUnless { it.isNullOrBlank() } ?: LINE_NAME_PRIOR_DEBT

                    val uncataloged = UncatalogedSaleBuilder.build(
                        saleId = saleId,
                        itemId = itemId,
                        chargeId = chargeId,
                        businessId = businessId,
                        customerId = customer.id,
                        sellerUserId = userId,
                        amountCents = amountCents,
                        saleNumber = "SA-$nowEpochMillis-${op.lineIndex}",
                        saleDateEpochDay = effectiveDate,
                        todayEpochDay = todayEpochDay,
                        nowEpochMillis = nowEpochMillis,
                        dueDateEpochDay = null,
                        notes = null,
                        lineNameSnapshot = lineDescription,
                    )
                    sales.add(uncataloged.sale)
                    saleItems.add(uncataloged.item)
                    chargeMovements.add(uncataloged.charge)

                    val runningList = runningOutstandingSalesByCustomerId.getOrPut(customer.id) { mutableListOf() }
                    runningList.add(OutstandingSale(saleId, amountCents, null, effectiveDate))
                }
                ProposedLedgerKind.PAYMENT -> {
                    val paymentId = SaleOperationIds.payment(opKey)
                    val movementId = SaleOperationIds.paymentMovement(opKey)

                    val runningList = runningOutstandingSalesByCustomerId.getOrPut(customer.id) { mutableListOf() }
                    val allocationResult = PaymentAllocator.allocate(amountCents, runningList)

                    if (allocationResult.creditCents > 0L) {
                        // Overpayment in atomic batch is not allowed
                        return BatchPlanningResult.Rejected(
                            listOf(BatchPlanningRejection.FinancialError(op.lineIndex, FinancialRejectionReason.PAYMENT_EXCEEDS_DEBT))
                        )
                    }

                    // Update running list by reducing outstandingCents on allocated sales
                    val allocatedMap = allocationResult.allocations.associate { it.saleId to it.amountCents }
                    val updatedRunningList = runningList.map { sale ->
                        val allocated = allocatedMap[sale.id] ?: 0L
                        if (allocated > 0L) {
                            sale.copy(outstandingCents = sale.outstandingCents - allocated)
                        } else {
                            sale
                        }
                    }.filter { it.outstandingCents > 0L }.toMutableList()
                    runningOutstandingSalesByCustomerId[customer.id] = updatedRunningList

                    val payment = PaymentEntity(
                        id = paymentId,
                        businessId = businessId,
                        customerId = customer.id,
                        amountCents = amountCents,
                        paymentMethod = "CASH",
                        paymentDateEpochDay = effectiveDate,
                        reference = null,
                        notes = null,
                        createdByUserId = userId,
                        createdAtEpochMillis = nowEpochMillis,
                        canceledAtEpochMillis = null,
                    )
                    val paymentMovement = AccountMovementEntity(
                        id = movementId,
                        businessId = businessId,
                        customerId = customer.id,
                        saleId = null,
                        paymentId = paymentId,
                        type = "PAYMENT",
                        amountCents = amountCents,
                        direction = "CREDIT",
                        occurredAtEpochMillis = nowEpochMillis,
                        createdByUserId = userId,
                        reversesMovementId = null,
                        notes = null,
                    )
                    val opAllocations = allocationResult.allocations.map { alloc ->
                        PaymentAllocationEntity(
                            id = SaleOperationIds.allocation(opKey, alloc.saleId),
                            paymentId = paymentId,
                            saleId = alloc.saleId,
                            amountCents = alloc.amountCents,
                        )
                    }

                    payments.add(payment)
                    paymentMovements.add(paymentMovement)
                    paymentAllocations.addAll(opAllocations)
                }
                null -> {}
            }
        }

        val plan = PlannedLedgerBatch(
            batchIdempotencyKey = batchKey,
            businessId = businessId,
            sellerUserId = userId,
            nowEpochMillis = nowEpochMillis,
            todayEpochDay = todayEpochDay,
            newCustomers = Collections.unmodifiableList(newCustomers),
            sales = Collections.unmodifiableList(sales),
            saleItems = Collections.unmodifiableList(saleItems),
            chargeMovements = Collections.unmodifiableList(chargeMovements),
            payments = Collections.unmodifiableList(payments),
            paymentMovements = Collections.unmodifiableList(paymentMovements),
            paymentAllocations = Collections.unmodifiableList(paymentAllocations),
            skippedRowIndices = Collections.unmodifiableList(skippedRowIndices),
            executableOperationsCount = registrableOps.size,
        )

        return BatchPlanningResult.Success(plan)
    }

    private fun toEpochDay(candidate: SafeDateCandidate): Long? {
        return runCatching {
            LocalDate.of(candidate.year, candidate.month, candidate.day).toEpochDay()
        }.getOrNull()
    }
}
