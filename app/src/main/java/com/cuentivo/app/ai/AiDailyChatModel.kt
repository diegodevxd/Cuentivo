package com.cuentivo.app.ai

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Non-financial daily-chat message kinds. Only local chat copy is ever stored: no proposal JSON,
 * no money, no customer/product IDs. Greeting, out-of-scope and unavailable render deterministic
 * constants in the UI, so they persist only their kind.
 */
enum class AiDailyMessageKind {
    USER_TEXT,
    ASSISTANT_GREETING,
    ASSISTANT_OUT_OF_SCOPE,
    ASSISTANT_UNAVAILABLE,
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