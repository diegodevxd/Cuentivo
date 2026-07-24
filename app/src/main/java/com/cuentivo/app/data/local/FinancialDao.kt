package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface FinancialDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun reserveOperation(operation: OperationEntity): Long

    @Query("SELECT resultId FROM operations WHERE idempotencyKey = :key")
    suspend fun operationResultId(key: String): String?

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

    @Query("SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END), 0) FROM account_movements WHERE businessId = :businessId AND customerId = :customerId")
    suspend fun customerBalanceCents(businessId: String, customerId: String): Long

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
        return payment.id
    }
}
