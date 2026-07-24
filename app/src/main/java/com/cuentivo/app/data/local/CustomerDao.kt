package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(customer: CustomerEntity)

    @Update
    suspend fun update(customer: CustomerEntity)

    @Query("SELECT * FROM customers WHERE businessId = :businessId AND deletedAtEpochMillis IS NULL AND isActive = 1 AND (normalizedName LIKE '%' || :nameQuery || '%' OR COALESCE(phone, '') LIKE '%' || :phoneQuery || '%') ORDER BY name")
    suspend fun searchActive(businessId: String, nameQuery: String, phoneQuery: String): List<CustomerEntity>

    @Query("SELECT customer.*, COALESCE(SUM(CASE WHEN movement.direction = 'DEBIT' THEN movement.amountCents ELSE -movement.amountCents END), 0) AS balanceCents FROM customers AS customer LEFT JOIN account_movements AS movement ON movement.customerId = customer.id AND movement.businessId = customer.businessId WHERE customer.businessId = :businessId AND customer.deletedAtEpochMillis IS NULL AND customer.isActive = 1 GROUP BY customer.id ORDER BY balanceCents DESC, customer.name")
    suspend fun activeWithBalances(businessId: String): List<CustomerBalanceRow>

    @Query("UPDATE customers SET isActive = 0, deletedAtEpochMillis = :archivedAtEpochMillis, updatedAtEpochMillis = :archivedAtEpochMillis WHERE id = :customerId")
    suspend fun archive(customerId: String, archivedAtEpochMillis: Long)
}

data class CustomerBalanceRow(
    @androidx.room.Embedded val customer: CustomerEntity,
    val balanceCents: Long,
)
