package com.cuentivo.app.ai

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily-chat message kinds for local conversation history.
 *
 * - [USER_TEXT]: locally retained user-provided business text; may include user-written names,
 *   customer references or financial amounts (e.g. "María abonó 150"). Retained locally for 30
 *   days, subject to cleanup/manual delete. Never uploaded or synced to Firestore, gateway,
 *   Analytics, Crashlytics or logs. No audio or photo is persisted.
 * - Assistant deterministic kinds ([ASSISTANT_GREETING], [ASSISTANT_OUT_OF_SCOPE],
 *   [ASSISTANT_UNAVAILABLE]): have no raw model output or proposal payload and render
 *   deterministic constants in the UI, persisting only their kind.
 *
 * Local daily chat is an operational assistance transcript and is not a financial source of truth;
 * real balances, operations and customer ledgers remain strictly in domain/audit tables.
 */
enum class AiDailyMessageKind {
    USER_TEXT,
    ASSISTANT_GREETING,
    ASSISTANT_OUT_OF_SCOPE,
    ASSISTANT_UNAVAILABLE,
    ASSISTANT_QUOTA_EXHAUSTED,
    ASSISTANT_RATE_LIMITED,
    ASSISTANT_DISABLED,
    ASSISTANT_ENTITLEMENT_REQUIRED,
}

/**
 * Pure day-key arithmetic for the local 30-day chat history. The day is the device-local calendar
 * date, never UTC. Retention is exact: with today = 2026-08-23 and 30 days, the kept window is
 * 2026-07-25..2026-08-23 and cleanup deletes only dayIso &lt; 2026-07-25.
 */
object AiDailyChatModel {

    /** The device-local calendar day of [epochMillis] in [zoneId], as an ISO string. */
    fun dayIsoOf(epochMillis: Long, zoneId: ZoneId): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zoneId).toString()

    /**
     * Oldest day still retained, as an ISO string. Anything strictly before it is expired.
     * [retainDays] must be positive; with 30 it keeps today plus the previous 29 days.
     */
    fun cutoffDayIso(todayIso: String, retainDays: Long = 30): String {
        require(retainDays > 0) { "retainDays must be positive, got $retainDays" }
        return LocalDate.parse(todayIso).minusDays(retainDays - 1).toString()
    }
}