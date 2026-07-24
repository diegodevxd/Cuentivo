package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BusinessDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(business: BusinessEntity)

    @Query("SELECT * FROM businesses ORDER BY createdAtEpochMillis LIMIT 1")
    suspend fun first(): BusinessEntity?
}
