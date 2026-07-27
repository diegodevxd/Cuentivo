package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cuentivo.app.domain.AuditAction
import com.cuentivo.app.domain.AuditTrail

/**
 * A sale with money still owed on it. Mirrors domain.OutstandingSale, which PaymentAllocator takes;
 * kept separate so the domain layer stays free of Room.
 */
data class OutstandingSaleRow(
    val id: String,
    val outstandingCents: Long,
    val dueDateEpochDay: Long?,
    val saleDateEpochDay: Long,
)

/** A dated sale that still owes money, with whose sale it is. Feeds the alerts screen. */
data class DueSaleRow(
    val saleId: String,
    val customerId: String,
    val customerName: String,
    val dueDateEpochDay: Long,
    val outstandingCents: Long,
)

/** A sale as offered for correction on screen: what it was worth and what is still unpaid. */
data class CancelableSaleRow(
    val id: String,
    val saleNumber: String,
    val saleDateEpochDay: Long,
    val totalCents: Long,
    val outstandingCents: Long,
)

@Dao
interface FinancialDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun reserveOperation(operation: OperationEntity): Long

    @Query("SELECT resultId FROM operations WHERE idempotencyKey = :key")
    suspend fun operationResultId(key: String): String?

    /**
     * IGNORE, not ABORT: the audit id is derived from the idempotency key, so a replayed operation
     * must find its event already there and carry on rather than blowing up the transaction.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAuditEvent(event: AuditLogEntity)

    /**
     * Records who did what, inside the caller's transaction.
     *
     * Being in the same transaction as the financial write is the whole point: an audit row that
     * can be lost independently of the movement it describes is not an audit trail.
     */
    suspend fun recordAudit(
        operation: OperationEntity,
        action: AuditAction,
        businessId: String,
        entityType: String,
        entityId: String,
        userId: String,
        timestampEpochMillis: Long,
    ) {
        insertAuditEvent(
            AuditLogEntity(
                id = AuditTrail.eventId(operation.idempotencyKey),
                businessId = businessId,
                entityType = entityType,
                entityId = entityId,
                action = action.code,
                userId = userId,
                timestampEpochMillis = timestampEpochMillis,
                summary = action.summary,
            )
        )
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSale(sale: SaleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSaleItems(items: List<SaleItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPayment(payment: PaymentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllocations(allocations: List<PaymentAllocationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMovement(movement: AccountMovementEntity)

    // Remote adoption uses IGNORE, not ABORT: a document that already exists locally is the normal
    // reconvergence case, not an error. Nothing here can overwrite an existing financial row, which
    // is what keeps the ledger append-only even when the write originates on another device.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun adoptSale(sale: SaleEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun adoptSaleItem(item: SaleItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun adoptPayment(payment: PaymentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun adoptAllocation(allocation: PaymentAllocationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun adoptMovement(movement: AccountMovementEntity)

    @Query("SELECT * FROM sale_items WHERE id = :itemId")
    suspend fun saleItemForId(itemId: String): SaleItemEntity?

    @Query("SELECT * FROM payment_allocations WHERE id = :allocationId")
    suspend fun allocationForId(allocationId: String): PaymentAllocationEntity?

    @Query("SELECT * FROM account_movements WHERE id = :movementId")
    suspend fun movementForId(movementId: String): AccountMovementEntity?

    @Query("SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END), 0) FROM account_movements WHERE businessId = :businessId AND customerId = :customerId")
    suspend fun customerBalanceCents(businessId: String, customerId: String): Long

    @Query("SELECT * FROM account_movements WHERE businessId = :businessId AND customerId = :customerId ORDER BY occurredAtEpochMillis DESC")
    suspend fun customerMovements(businessId: String, customerId: String): List<AccountMovementEntity>

    @Query("SELECT COALESCE(SUM(CASE WHEN balanceCents > 0 THEN balanceCents ELSE 0 END), 0) FROM (SELECT customerId, SUM(CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END) AS balanceCents FROM account_movements WHERE businessId = :businessId GROUP BY customerId)")
    suspend fun businessReceivableCents(businessId: String): Long

    @Query("SELECT COUNT(*) FROM (SELECT customerId FROM account_movements WHERE businessId = :businessId GROUP BY customerId HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END) > 0)")
    suspend fun customersWithDebtCount(businessId: String): Long

    @Query("SELECT COALESCE(SUM(totalCents), 0) FROM sales WHERE businessId = :businessId AND isCanceled = 0 AND saleDateEpochDay BETWEEN :startEpochDay AND :endEpochDay")
    suspend fun salesTotalCents(businessId: String, startEpochDay: Long, endEpochDay: Long): Long

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM payments WHERE businessId = :businessId AND canceledAtEpochMillis IS NULL AND paymentDateEpochDay BETWEEN :startEpochDay AND :endEpochDay")
    suspend fun collectedTotalCents(businessId: String, startEpochDay: Long, endEpochDay: Long): Long

    @Query("SELECT COALESCE(SUM(CASE WHEN outstandingCents > 0 THEN outstandingCents ELSE 0 END), 0) FROM (SELECT sale.id, sale.totalCents - COALESCE(SUM(CASE WHEN payment.canceledAtEpochMillis IS NULL THEN allocation.amountCents ELSE 0 END), 0) AS outstandingCents FROM sales AS sale LEFT JOIN payment_allocations AS allocation ON allocation.saleId = sale.id LEFT JOIN payments AS payment ON payment.id = allocation.paymentId WHERE sale.businessId = :businessId AND sale.isCanceled = 0 AND sale.dueDateEpochDay IS NOT NULL AND sale.dueDateEpochDay < :todayEpochDay GROUP BY sale.id)")
    suspend fun overdueBalanceCents(businessId: String, todayEpochDay: Long): Long

    @Query("SELECT * FROM sales WHERE id = :saleId")
    suspend fun saleForId(saleId: String): SaleEntity?

    /**
     * The customer's sales that still owe something, for feeding PaymentAllocator.
     *
     * Same shape as the subquery in overdueBalanceCents, minus the due-date filter: a payment is
     * applied to whatever is unpaid, whether or not it is late yet. Canceled payments contribute
     * nothing, so cancelling a payment puts its sale back in the queue automatically.
     */
    @Query(
        "SELECT sale.id AS id, " +
            "sale.totalCents - COALESCE(SUM(CASE WHEN payment.canceledAtEpochMillis IS NULL THEN allocation.amountCents ELSE 0 END), 0) AS outstandingCents, " +
            "sale.dueDateEpochDay AS dueDateEpochDay, sale.saleDateEpochDay AS saleDateEpochDay " +
            "FROM sales AS sale " +
            "LEFT JOIN payment_allocations AS allocation ON allocation.saleId = sale.id " +
            "LEFT JOIN payments AS payment ON payment.id = allocation.paymentId " +
            "WHERE sale.businessId = :businessId AND sale.customerId = :customerId AND sale.isCanceled = 0 " +
            "GROUP BY sale.id HAVING outstandingCents > 0"
    )
    suspend fun outstandingSalesForCustomer(businessId: String, customerId: String): List<OutstandingSaleRow>

    /**
     * The customer's sales that can still be corrected from the screen.
     *
     * Only sales with something unpaid are offered: cancelSaleIdempotently compensates exactly the
     * unpaid remainder, so a fully collected sale would produce a zero movement that says nothing
     * about the money already received. That case is a refund, which is cancelling the payment.
     */
    @Query(
        "SELECT sale.id AS id, sale.saleNumber AS saleNumber, sale.saleDateEpochDay AS saleDateEpochDay, " +
            "sale.totalCents AS totalCents, " +
            "sale.totalCents - COALESCE(SUM(CASE WHEN payment.canceledAtEpochMillis IS NULL THEN allocation.amountCents ELSE 0 END), 0) AS outstandingCents " +
            "FROM sales AS sale " +
            "LEFT JOIN payment_allocations AS allocation ON allocation.saleId = sale.id " +
            "LEFT JOIN payments AS payment ON payment.id = allocation.paymentId " +
            "WHERE sale.businessId = :businessId AND sale.customerId = :customerId AND sale.isCanceled = 0 " +
            "GROUP BY sale.id HAVING outstandingCents > 0 ORDER BY sale.saleDateEpochDay DESC, sale.id"
    )
    suspend fun cancelableSalesForCustomer(businessId: String, customerId: String): List<CancelableSaleRow>

    /** The customer's payments that have not been reversed yet. */
    @Query("SELECT * FROM payments WHERE businessId = :businessId AND customerId = :customerId AND canceledAtEpochMillis IS NULL ORDER BY paymentDateEpochDay DESC, id")
    suspend fun activePaymentsForCustomer(businessId: String, customerId: String): List<PaymentEntity>

    /**
     * Every sale ever written for the business, for the free-plan limit.
     *
     * Canceled sales count too: cancelling must not become a way to reset the counter.
     */
    @Query("SELECT COUNT(*) FROM sales WHERE businessId = :businessId")
    suspend fun registeredSaleCount(businessId: String): Int

    /**
     * Every sale that still owes something and has a date attached, across the whole business.
     *
     * `overdueBalanceCents` answers "how much is late" with a single number; this answers "who, and
     * when", which is what an alert has to say to be worth tapping. Read-only, no schema change.
     *
     * Cancelled payments are excluded from the allocated sum, so a refunded payment correctly makes
     * its sale owe money again.
     */
    @Query(
        "SELECT sale.id AS saleId, sale.dueDateEpochDay AS dueDateEpochDay, " +
            "customer.name AS customerName, customer.id AS customerId, " +
            "sale.totalCents - COALESCE(SUM(CASE WHEN payment.canceledAtEpochMillis IS NULL THEN allocation.amountCents ELSE 0 END), 0) AS outstandingCents " +
            "FROM sales AS sale " +
            // Both halves of this join are load-bearing. There are no @ForeignKey constraints
            // anywhere in this schema — integrity is enforced by require(...) inside the FinancialDao
            // transactions — so nothing at the database level stops a row from pointing at a customer
            // that belongs to another business. Matching on businessId too means such a row produces
            // no alert, instead of leaking another business's customer name onto this screen.
            "INNER JOIN customers AS customer ON customer.id = sale.customerId " +
            "AND customer.businessId = sale.businessId " +
            "LEFT JOIN payment_allocations AS allocation ON allocation.saleId = sale.id " +
            "LEFT JOIN payments AS payment ON payment.id = allocation.paymentId " +
            "WHERE sale.businessId = :businessId AND sale.isCanceled = 0 AND sale.dueDateEpochDay IS NOT NULL " +
            "GROUP BY sale.id HAVING outstandingCents > 0 ORDER BY sale.dueDateEpochDay"
    )
    suspend fun salesWithDueDate(businessId: String): List<DueSaleRow>

    @Query("SELECT COALESCE(SUM(allocation.amountCents), 0) FROM payment_allocations AS allocation INNER JOIN payments AS payment ON payment.id = allocation.paymentId WHERE allocation.saleId = :saleId AND payment.canceledAtEpochMillis IS NULL")
    suspend fun activeAllocatedPaymentCents(saleId: String): Long

    @Query("SELECT COUNT(*) FROM account_movements WHERE saleId = :saleId")
    suspend fun movementCountForSale(saleId: String): Int

    @Query("SELECT * FROM payments WHERE id = :paymentId")
    suspend fun paymentForId(paymentId: String): PaymentEntity?

    @Query("SELECT COUNT(*) FROM account_movements WHERE paymentId = :paymentId")
    suspend fun movementCountForPayment(paymentId: String): Int

    @Query("UPDATE payments SET canceledAtEpochMillis = :canceledAtEpochMillis WHERE id = :paymentId")
    suspend fun markPaymentCanceled(paymentId: String, canceledAtEpochMillis: Long)

    @Query("UPDATE sales SET isCanceled = 1, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :saleId")
    suspend fun markSaleCanceled(saleId: String, updatedAtEpochMillis: Long)

    @Transaction
    suspend fun insertSaleWithCharge(sale: SaleEntity, items: List<SaleItemEntity>, charge: AccountMovementEntity) {
        insertSale(sale)
        insertSaleItems(items)
        insertMovement(charge)
    }

    @Transaction
    suspend fun insertPaymentWithMovements(payment: PaymentEntity, allocations: List<PaymentAllocationEntity>, movement: AccountMovementEntity) {
        insertPayment(payment)
        insertAllocations(allocations)
        insertMovement(movement)
    }
    /** Atomically reserves a key and creates the sale's financial records. */
    @Transaction
    suspend fun createSaleIdempotently(
        operation: OperationEntity,
        sale: SaleEntity,
        items: List<SaleItemEntity>,
        charge: AccountMovementEntity,
    ): String {
        if (reserveOperation(operation) == -1L) {
            return requireNotNull(operationResultId(operation.idempotencyKey))
        }
        insertSale(sale)
        insertSaleItems(items)
        insertMovement(charge)
        recordAudit(operation, AuditAction.SALE_CREATED, sale.businessId, "SALE", sale.id, sale.sellerUserId, sale.createdAtEpochMillis)
        return sale.id
    }

    /** Atomically reserves a key and creates a payment with its ledger entry. */
    @Transaction
    suspend fun registerPaymentIdempotently(
        operation: OperationEntity,
        payment: PaymentEntity,
        allocations: List<PaymentAllocationEntity>,
        movement: AccountMovementEntity,
    ): String {
        if (reserveOperation(operation) == -1L) {
            return requireNotNull(operationResultId(operation.idempotencyKey))
        }
        require(payment.amountCents > 0) { "Payment must be positive." }
        require(allocations.sumOf { it.amountCents } <= payment.amountCents) {
            "Allocations cannot exceed the payment amount."
        }
        allocations.groupBy { it.saleId }.forEach { (saleId, saleAllocations) ->
            val allocationCents = saleAllocations.sumOf { it.amountCents }
            saleAllocations.forEach { allocation ->
                require(allocation.paymentId == payment.id && allocation.amountCents > 0) {
                    "Allocation must belong to this payment and be positive."
                }
            }
            val sale = requireNotNull(saleForId(saleId)) { "Allocated sale does not exist." }
            require(sale.businessId == payment.businessId && sale.customerId == payment.customerId && !sale.isCanceled) {
                "Allocation sale must belong to the payment customer and business."
            }
            val outstandingCents = sale.totalCents - activeAllocatedPaymentCents(sale.id)
            require(allocationCents <= outstandingCents) {
                "Allocation cannot exceed the sale outstanding balance."
            }
        }
        insertPayment(payment)
        insertAllocations(allocations)
        insertMovement(movement)
        recordAudit(operation, AuditAction.PAYMENT_REGISTERED, payment.businessId, "PAYMENT", payment.id, payment.createdByUserId, payment.createdAtEpochMillis)
        return payment.id
    }

    /** Stores an immutable, documented balance correction without duplicating retries. */
    @Transaction
    suspend fun registerAdjustmentIdempotently(
        operation: OperationEntity,
        adjustment: AccountMovementEntity,
    ): String {
        if (reserveOperation(operation) == -1L) {
            return requireNotNull(operationResultId(operation.idempotencyKey))
        }
        require(adjustment.type == "ADJUSTMENT") { "Adjustment type is required." }
        require(adjustment.direction == "DEBIT" || adjustment.direction == "CREDIT") {
            "Adjustment direction must be DEBIT or CREDIT."
        }
        require(adjustment.amountCents > 0) { "Adjustment must be positive." }
        require(!adjustment.notes.isNullOrBlank()) { "Adjustment reason is required." }
        insertMovement(adjustment)
        recordAudit(operation, AuditAction.ADJUSTMENT_REGISTERED, adjustment.businessId, "MOVEMENT", adjustment.id, adjustment.createdByUserId, adjustment.occurredAtEpochMillis)
        return adjustment.id
    }

    /** Cancels only the unpaid amount; a refunded payment is a separate ledger event. */
    @Transaction
    suspend fun cancelSaleIdempotently(
        operation: OperationEntity,
        saleId: String,
        cancellation: AccountMovementEntity,
    ): String {
        if (reserveOperation(operation) == -1L) {
            return requireNotNull(operationResultId(operation.idempotencyKey))
        }
        val sale = requireNotNull(saleForId(saleId)) { "Sale does not exist." }
        require(!sale.isCanceled) { "Sale is already canceled." }
        require(cancellation.type == "CANCELLATION" && cancellation.direction == "CREDIT")
        require(cancellation.businessId == sale.businessId && cancellation.customerId == sale.customerId && cancellation.saleId == sale.id)
        val allocatedCents = activeAllocatedPaymentCents(sale.id)
        val outstandingCents = sale.totalCents - allocatedCents
        require(outstandingCents >= 0 && cancellation.amountCents == outstandingCents) { "Cancellation must compensate the unpaid amount." }
        markSaleCanceled(sale.id, cancellation.occurredAtEpochMillis)
        insertMovement(cancellation)
        recordAudit(operation, AuditAction.SALE_CANCELED, sale.businessId, "SALE", sale.id, cancellation.createdByUserId, cancellation.occurredAtEpochMillis)
        return sale.id
    }

    /** Reverses a payment with a debit ledger entry instead of deleting evidence of collection. */
    @Transaction
    suspend fun cancelPaymentIdempotently(
        operation: OperationEntity,
        paymentId: String,
        refund: AccountMovementEntity,
    ): String {
        if (reserveOperation(operation) == -1L) {
            return requireNotNull(operationResultId(operation.idempotencyKey))
        }
        val payment = requireNotNull(paymentForId(paymentId)) { "Payment does not exist." }
        require(payment.canceledAtEpochMillis == null) { "Payment is already canceled." }
        require(refund.type == "REFUND" && refund.direction == "DEBIT")
        require(refund.businessId == payment.businessId && refund.customerId == payment.customerId && refund.paymentId == payment.id)
        require(refund.amountCents == payment.amountCents) { "Refund must match the original payment." }
        markPaymentCanceled(payment.id, refund.occurredAtEpochMillis)
        insertMovement(refund)
        recordAudit(operation, AuditAction.PAYMENT_CANCELED, payment.businessId, "PAYMENT", payment.id, refund.createdByUserId, refund.occurredAtEpochMillis)
        return payment.id
    }
}
