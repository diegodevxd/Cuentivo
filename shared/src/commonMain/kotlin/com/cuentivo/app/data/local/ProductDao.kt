package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: ProductEntity)

    @Update
    suspend fun update(product: ProductEntity)

    @Query("SELECT * FROM products WHERE businessId = :businessId AND deletedAtEpochMillis IS NULL AND isActive = 1 AND (name LIKE '%' || :nameQuery || '%' OR COALESCE(sku, '') LIKE '%' || :skuQuery || '%') ORDER BY name")
    suspend fun searchActive(businessId: String, nameQuery: String, skuQuery: String): List<ProductEntity>

    @Query("UPDATE products SET isActive = 0, deletedAtEpochMillis = :archivedAtEpochMillis, updatedAtEpochMillis = :archivedAtEpochMillis WHERE id = :productId")
    suspend fun archive(productId: String, archivedAtEpochMillis: Long)

    @Query("SELECT * FROM products WHERE id = :productId")
    suspend fun byId(productId: String): ProductEntity?

    /** Every product, archived ones included. See `CustomerDao.allForBackfill` for why unfiltered. */
    @Query("SELECT * FROM products WHERE businessId = :businessId")
    suspend fun allForBackfill(businessId: String): List<ProductEntity>

    /** Adopts a remote product wholesale. Only call once the merge planner says the remote wins. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(product: ProductEntity)
}
