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
        insertPayment(payment)
        insertAllocations(allocations)
        insertMovement(movement)
        return payment.id
    }
}
