package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Local daily-chat persistence. Every query is scoped by ownerUserId; no financial table is ever
 * referenced. Retention is enforced by callers via [com.cuentivo.app.ai.AiDailyChatModel].
 */
@Dao
interface AiDailyMessageDao {

    @Insert
    suspend fun insert(message: AiDailyMessageEntity): Long

    @Query(
        "SELECT * FROM ai_daily_messages WHERE ownerUserId = :ownerUserId AND dayIso = :dayIso ORDER BY id ASC",
    )
    suspend fun messagesForDay(ownerUserId: String, dayIso: String): List<AiDailyMessageEntity>

    @Query("DELETE FROM ai_daily_messages WHERE ownerUserId = :ownerUserId AND dayIso = :dayIso")
    suspend fun deleteDay(ownerUserId: String, dayIso: String)

    @Query("DELETE FROM ai_daily_messages WHERE ownerUserId = :ownerUserId AND dayIso < :cutoffDayIso")
    suspend fun deleteExpiredDays(ownerUserId: String, cutoffDayIso: String)
}