package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface FinancialDao {
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

    @Query("SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END), 0) FROM account_movements WHERE customerId = :customerId")
    suspend fun customerBalanceCents(customerId: String): Long

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
}
