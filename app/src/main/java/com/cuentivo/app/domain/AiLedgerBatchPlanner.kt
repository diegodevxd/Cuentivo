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
    data class UnresolvedRows(val lineIndices: List<Int>) : BatchPlanningRejection

    /**
     * The row was anchored to a customer that is no longer among the existing ones.
     *
     * It happens when the ledger is reviewed and the customer is archived, deleted or otherwise
     * gone before the page is confirmed. Planning it anyway would build a CustomerEntity carrying
     * that id, and `insertCustomer` aborts on conflict, so the whole transaction would blow up with
     * an exception the person sees as "could not register the notebook" and nothing else. Rejecting
     * here keeps the failure deterministic and nameable.
     */
    data class UnknownCustomerAnchor(val lineIndex: Int, val customerId: String) : BatchPlanningRejection
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
     * La clave depende sólo del negocio y la sesión: el contenido editable no puede crear otra clave
     * después de que un commit se perdió y volver a escribir un cargo.
     *
     * Public because the caller needs it BEFORE planning: once a page is written, planning it again
     * sees the debt it already changed and would reject a payment that was in fact recorded.
     */
    fun batchKeyFor(businessId: String, importSessionId: String): String {
        return "ai-ledger-batch-" + UUID.nameUUIDFromBytes(
            "${field(businessId)}:${field(importSessionId)}".toByteArray(StandardCharsets.UTF_8)
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

    /**
     * @param importSessionId Stable identity of this reviewed page across retries.
     */
    fun plan(
        businessId: String,
        userId: String,
        importSessionId: String,
        operations: List<LedgerOperation>,
        blockedLineIndices: Set<Int>,
        existingCustomerMap: Map<String, CustomerEntity>, // keyed by normalizedCustomerName
        existingOutstandingSalesByCustomerId: Map<String, List<OutstandingSale>>,
        pageDateEpochDay: Long?,
        /**
         * Plazo de pago para los cargos de esta página, en días, o `null` para "Sin fecha"
         * (el comportamiento de siempre: la venta nace sin fecha límite y por eso no puede
         * vencerse). Un solo plazo para la página entera, elegido en la pantalla de revisión.
         *
         * Se aplica **sólo a los cargos**, y la fecha límite se cuenta desde la fecha efectiva del
         * renglón (`effectiveDate`, ya resuelta), **no desde hoy**: una venta del 1 de agosto a 15
         * días vence el 16 de agosto, que es lo que habría pasado en la libreta. La cuenta vive en
         * [LedgerDueDatePolicy], compartida con el aviso de "quedarían vencidas".
         */
        dueDateDays: Long? = null,
        todayEpochDay: Long,
        nowEpochMillis: Long,
    ): BatchPlanningResult {
        if (businessId.isBlank() || userId.isBlank()) {
            return BatchPlanningResult.Rejected(
                listOf(BatchPlanningRejection.BlankBusinessOrUser("Business ID and User ID must not be blank."))
            )
        }

        val rejections = mutableListOf<BatchPlanningRejection>()

        val unresolved = LedgerPageResolution.unresolvedLineIndices(operations, blockedLineIndices)
        if (unresolved.isNotEmpty()) {
            return BatchPlanningResult.Rejected(
                listOf(BatchPlanningRejection.UnresolvedRows(unresolved.sorted()))
            )
        }
        val registrableOps = operations

        if (operations.size > MAX_BATCH_OPERATIONS) {
            return BatchPlanningResult.Rejected(
                listOf(BatchPlanningRejection.ExceedsBatchCapacity(MAX_BATCH_OPERATIONS, operations.size))
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

        // 3. Group by customer normalized name or explicit customerId anchor
        val existingCustomersById = existingCustomerMap.values.associateBy { it.id }

        // An anchor names a customer a person picked from the list they were shown. If it is not
        // there any more, the page is planned against a customer nobody can point at, so it is
        // refused before a single entity is built rather than exploding inside the transaction.
        for (op in registrableOps) {
            val anchored = op.customerId
            if (!anchored.isNullOrBlank() && !existingCustomersById.containsKey(anchored)) {
                rejections.add(BatchPlanningRejection.UnknownCustomerAnchor(op.lineIndex, anchored))
            }
        }
        if (rejections.isNotEmpty()) {
            return BatchPlanningResult.Rejected(rejections)
        }
        val groupedOps = LinkedHashMap<String, MutableList<LedgerOperation>>()
        for (op in registrableOps) {
            val key = if (!op.customerId.isNullOrBlank()) {
                "id:${op.customerId}"
            } else {
                com.cuentivo.app.domain.CustomerNameNormalizer.key(op.customerName.orEmpty())
            }
            groupedOps.getOrPut(key) { mutableListOf() }.add(op)
        }

        // First pass: check for any financial errors across all groups
        for ((groupKey, ops) in groupedOps) {
            val sampleOp = ops.first()
            val existingCust = if (!sampleOp.customerId.isNullOrBlank()) {
                existingCustomersById[sampleOp.customerId]
            } else {
                existingCustomerMap[groupKey]
            }
            val existingSales = existingCust?.id?.let { existingOutstandingSalesByCustomerId[it] }.orEmpty()
            // sumOf would wrap silently, and a wrapped balance is worse than a rejected page: a
            // debt that overflows into a negative number turns a legitimate payment into
            // PAYMENT_WITHOUT_DEBT, and one that overflows the other way accepts a payment larger
            // than anything actually owed. Same treatment the running total already gets below.
            var runningBalance = 0L
            var openingOverflowed = false
            for (sale in existingSales) {
                if (sale.outstandingCents <= 0L) continue
                try {
                    runningBalance = Math.addExact(runningBalance, sale.outstandingCents)
                } catch (_: ArithmeticException) {
                    openingOverflowed = true
                    break
                }
            }
            if (openingOverflowed) {
                rejections.add(
                    BatchPlanningRejection.FinancialError(ops.first().lineIndex, FinancialRejectionReason.OVERFLOW)
                )
                continue
            }

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
        val batchKey = batchKeyFor(businessId, importSessionId)

        // 5. Build entities
        val newCustomers = mutableListOf<CustomerEntity>()
        val createdCustomerMap = mutableMapOf<String, CustomerEntity>()

        // Resolve customer entities (deduplicated by normalized name or explicit anchor)
        for ((groupKey, ops) in groupedOps) {
            val sampleOp = ops.first()
            val existing = if (!sampleOp.customerId.isNullOrBlank()) {
                existingCustomersById[sampleOp.customerId]
            } else {
                existingCustomerMap[groupKey]
            }
            if (existing != null) {
                createdCustomerMap[groupKey] = existing
            } else {
                val displayName = ops.firstOrNull { !it.customerName.isNullOrBlank() }?.customerName?.trim() ?: groupKey
                // No anchor can reach here: an anchored group always resolves above, and one whose
                // customer vanished was rejected before anything was built. A new customer is only
                // ever minted for a name the notebook brought and nobody matched.
                val customerId = UUID.nameUUIDFromBytes(
                    "$batchKey:customer:$groupKey".toByteArray(StandardCharsets.UTF_8)
                ).toString()
                val newCust = CustomerEntity(
                    id = customerId,
                    businessId = businessId,
                    name = displayName,
                    normalizedName = groupKey,
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
                createdCustomerMap[groupKey] = newCust
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
        for ((groupKey, cust) in createdCustomerMap) {
            val existing = existingOutstandingSalesByCustomerId[cust.id].orEmpty().map {
                OutstandingSale(it.id, it.outstandingCents, it.dueDateEpochDay, it.saleDateEpochDay)
            }.toMutableList()
            runningOutstandingSalesByCustomerId[cust.id] = existing
        }

        // Process operations in order
        for (op in registrableOps) {
            val groupKey = if (!op.customerId.isNullOrBlank()) "id:${op.customerId}" else com.cuentivo.app.domain.CustomerNameNormalizer.key(op.customerName.orEmpty())
            val customer = checkNotNull(createdCustomerMap[groupKey]) { "Customer must be resolved" }
            val amountCents = checkNotNull(op.amountCents?.toLongOrNull())
            val effectiveDate = checkNotNull(resolvedDatesByLineIndex[op.lineIndex])
            val opKey = "$batchKey:${op.lineIndex}"

            when (op.proposedKind) {
                ProposedLedgerKind.SALE -> {
                    val saleId = SaleOperationIds.sale(opKey)
                    val chargeId = SaleOperationIds.chargeMovement(opKey)
                    val itemId = SaleOperationIds.uncatalogedSaleItem(opKey, 0)
                    // Several products say more than any single description could, so they win.
                    // One product does not, so a description the model wrote is preferred and the
                    // item only steps in when there is no description at all -- which is a shape the
                    // contract allows and where the product used to be dropped on the floor.
                    // Words only: the amount comes from the notebook and is never touched here.
                    val itemsSummary = LedgerItemsDescription.summarize(op.items)
                    val writtenDescription = op.description?.trim().takeUnless { it.isNullOrBlank() }
                    val lineDescription = when {
                        op.items.size >= 2 && itemsSummary != null -> itemsSummary
                        writtenDescription != null -> writtenDescription
                        itemsSummary != null -> itemsSummary
                        else -> LINE_NAME_SALE
                    }

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
                        dueDateEpochDay = LedgerDueDatePolicy.dueDateEpochDay(effectiveDate, dueDateDays),
                        notes = null,
                        lineNameSnapshot = lineDescription,
                    )
                    sales.add(uncataloged.sale)
                    saleItems.add(uncataloged.item)
                    chargeMovements.add(uncataloged.charge)

                    // Add new sale to running outstanding sales for this customer. The due date goes
                    // in here too: PaymentAllocator orders by `dueDateEpochDay ?: Long.MAX_VALUE`, so
                    // a running list that kept null would allocate payments in a different order than
                    // the sales actually stored with a due date.
                    val runningList = runningOutstandingSalesByCustomerId.getOrPut(customer.id) { mutableListOf() }
                    runningList.add(
                        OutstandingSale(
                            saleId,
                            amountCents,
                            LedgerDueDatePolicy.dueDateEpochDay(effectiveDate, dueDateDays),
                            effectiveDate,
                        ),
                    )
                }
                ProposedLedgerKind.PRIOR_DEBT -> {
                    val saleId = SaleOperationIds.sale(opKey)
                    val chargeId = SaleOperationIds.chargeMovement(opKey)
                    val itemId = SaleOperationIds.uncatalogedSaleItem(opKey, 0)
                    // Several products say more than any single description could, so they win.
                    // One product does not, so a description the model wrote is preferred and the
                    // item only steps in when there is no description at all -- which is a shape the
                    // contract allows and where the product used to be dropped on the floor.
                    // Words only: the amount comes from the notebook and is never touched here.
                    val itemsSummary = LedgerItemsDescription.summarize(op.items)
                    val writtenDescription = op.description?.trim().takeUnless { it.isNullOrBlank() }
                    val lineDescription = when {
                        op.items.size >= 2 && itemsSummary != null -> itemsSummary
                        writtenDescription != null -> writtenDescription
                        itemsSummary != null -> itemsSummary
                        else -> LINE_NAME_PRIOR_DEBT
                    }

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
                        dueDateEpochDay = LedgerDueDatePolicy.dueDateEpochDay(effectiveDate, dueDateDays),
                        // La deuda anterior se marca igual que la que crea `OpeningBalance` al dar de
                        // alta a una clienta. Sin el marcador, los lectores que ya existen no la
                        // reconocían: el historial la pintaba como "Venta", `getRegisteredOperationsInRange`
                        // la clasificaba como SALE y "Vendido del mes" la sumaba como venta nueva.
                        // Es el mismo texto almacenado y no traducido que usa `OpeningBalance`.
                        notes = OpeningBalance.NOTES_MARKER,
                        lineNameSnapshot = lineDescription,
                    )
                    sales.add(uncataloged.sale)
                    saleItems.add(uncataloged.item)
                    chargeMovements.add(uncataloged.charge)

                    // Same due date as the stored sale, for the same allocation-order reason above.
                    val runningList = runningOutstandingSalesByCustomerId.getOrPut(customer.id) { mutableListOf() }
                    runningList.add(
                        OutstandingSale(
                            saleId,
                            amountCents,
                            LedgerDueDatePolicy.dueDateEpochDay(effectiveDate, dueDateDays),
                            effectiveDate,
                        ),
                    )
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
            executableOperationsCount = registrableOps.size,
        )

        return BatchPlanningResult.Success(plan)
    }

    /**
     * Length-prefixes a free-text field so the digest cannot be made ambiguous by its contents.
     * Prefix-free, so no combination of names, descriptions and separators can encode to the same
     * string as a different combination.
     */
    private fun field(value: String): String = "${value.length}:$value"

    private fun toEpochDay(candidate: SafeDateCandidate): Long? {
        return runCatching {
            LocalDate.of(candidate.year, candidate.month, candidate.day).toEpochDay()
        }.getOrNull()
    }
}
