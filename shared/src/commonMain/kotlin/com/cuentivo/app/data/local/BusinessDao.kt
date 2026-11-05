package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BusinessDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(business: BusinessEntity)

    /**
     * The only business this device holds, whoever owns it.
     *
     * Safe for background work such as SyncWorker, which runs after LocalSessionGuard has already
     * cleared any other person's rows, so the table holds a single business. Screens must never use
     * this: use [firstOwnedBy] instead, or a second account signing in on the same phone inherits
     * the first one's customers and sales.
     */
    @Query("SELECT * FROM businesses ORDER BY createdAtEpochMillis LIMIT 1")
    suspend fun first(): BusinessEntity?

    /** The signed-in person's own business. Scoping by owner is what keeps two accounts apart. */
    @Query("SELECT * FROM businesses WHERE ownerUserId = :userId ORDER BY createdAtEpochMillis LIMIT 1")
    suspend fun firstOwnedBy(userId: String): BusinessEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(member: BusinessMemberEntity): Long

    @Query("SELECT * FROM business_members WHERE businessId = :businessId AND userId = :userId LIMIT 1")
    suspend fun memberFor(businessId: String, userId: String): BusinessMemberEntity?

    @Query("SELECT * FROM businesses WHERE id = :businessId")
    suspend fun byId(businessId: String): BusinessEntity?

    /** Every membership of the business. Feeds the outbox backfill. */
    @Query("SELECT * FROM business_members WHERE businessId = :businessId")
    suspend fun membersForBackfill(businessId: String): List<BusinessMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(business: BusinessEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: BusinessMemberEntity)
}
