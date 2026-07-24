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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(member: BusinessMemberEntity): Long

    @Query("SELECT * FROM business_members WHERE businessId = :businessId AND userId = :userId LIMIT 1")
    suspend fun memberFor(businessId: String, userId: String): BusinessMemberEntity?

    @Query("SELECT * FROM businesses WHERE id = :businessId")
    suspend fun byId(businessId: String): BusinessEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(business: BusinessEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: BusinessMemberEntity)
}
