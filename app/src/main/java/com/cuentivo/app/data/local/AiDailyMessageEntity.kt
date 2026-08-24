package com.cuentivo.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One non-financial daily-chat message, scoped by owner and device-local day.
 *
 * [kind] holds an [com.cuentivo.app.ai.AiDailyMessageKind] name; [textContent] is local chat copy
 * only (USER_TEXT) and never proposal JSON, money, customer or product IDs. No parent table, no
 * foreign key, no sync/outbox, no financial writes. Greeting, out-of-scope and unavailable rows
 * persist only their kind: the UI renders deterministic constants.
 */
@Entity(
    tableName = "ai_daily_messages",
    indices = [Index(value = ["ownerUserId", "dayIso", "id"])],
)
data class AiDailyMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerUserId: String,
    val dayIso: String,
    val kind: String,
    val textContent: String?,
    val createdAtEpochMillis: Long,
)