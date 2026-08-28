package com.cuentivo.app.ai

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiDailyChatModelTest {

    @Test
    fun `dayIsoOf uses the given zone not UTC`() {
        // 2026-08-23T02:00Z is still 2026-08-22 in Mexico City (UTC-6).
        val epoch = Instant.parse("2026-08-23T02:00:00Z").toEpochMilli()
        assertEquals(
            "2026-08-22",
            AiDailyChatModel.dayIsoOf(epoch, ZoneId.of("America/Mexico_City")),
        )
        assertEquals(
            "2026-08-23",
            AiDailyChatModel.dayIsoOf(epoch, ZoneOffset.UTC),
        )
    }

    @Test
    fun `cutoff retains exactly thirty days including today`() {
        // today = 2026-08-23 -> keep 2026-07-25..2026-08-23; delete only dayIso < 2026-07-25.
        assertEquals("2026-07-25", AiDailyChatModel.cutoffDayIso("2026-08-23", retainDays = 30))
    }

    @Test
    fun `cutoff keeps today when retainDays is one`() {
        assertEquals("2026-08-23", AiDailyChatModel.cutoffDayIso("2026-08-23", retainDays = 1))
    }

    @Test
    fun `cutoff rejects non positive retention`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiDailyChatModel.cutoffDayIso("2026-08-23", retainDays = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiDailyChatModel.cutoffDayIso("2026-08-23", retainDays = -3)
        }
    }

    @Test
    fun `cutoff rejects malformed iso dates`() {
        assertThrows(DateTimeException::class.java) {
            AiDailyChatModel.cutoffDayIso("23/08/2026", retainDays = 30)
        }
        assertThrows(DateTimeException::class.java) {
            AiDailyChatModel.cutoffDayIso("2026-13-01", retainDays = 30)
        }
    }

    @Test
    fun `cutoff crosses year boundaries`() {
        assertEquals("2025-12-27", AiDailyChatModel.cutoffDayIso("2026-01-25", retainDays = 30))
    }

    @Test
    fun `kinds enum contains exactly the allowed daily message kinds`() {
        val names = AiDailyMessageKind.entries.map { it.name }
        assertEquals(
            listOf(
                "USER_TEXT",
                "ASSISTANT_GREETING",
                "ASSISTANT_OUT_OF_SCOPE",
                "ASSISTANT_UNAVAILABLE",
                "ASSISTANT_QUOTA_EXHAUSTED",
                "ASSISTANT_RATE_LIMITED",
                "ASSISTANT_DISABLED",
                "ASSISTANT_ENTITLEMENT_REQUIRED",
            ),
            names,
        )
    }
}