package com.cuentivo.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One local daily-chat message entity, scoped by owner and device-local day.
 *
 * [kind] holds an [com.cuentivo.app.ai.AiDailyMessageKind] name.
 * - For [com.cuentivo.app.ai.AiDailyMessageKind.USER_TEXT], [textContent] contains user-provided
 *   business text, which may naturally include names or financial amounts typed by the user. It is
 *   stored locally for up to 30 days and is never synced or uploaded to Firestore, gateway,
 *   Analytics, Crashlytics or logs. No audio or photo data is persisted.
 * - Assistant kinds ([com.cuentivo.app.ai.AiDailyMessageKind.ASSISTANT_GREETING],
 *   [com.cuentivo.app.ai.AiDailyMessageKind.ASSISTANT_OUT_OF_SCOPE],
 *   [com.cuentivo.app.ai.AiDailyMessageKind.ASSISTANT_UNAVAILABLE]) store `null` [textContent];
 *   they contain no raw model output or proposal payload and render deterministic strings in UI.
 *
 * This table is completely isolated (no foreign keys, no sync/outbox tracking, no parent tables)
 * and is not a financial source of truth. Real balances and movements remain in domain/audit
 * entities.
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