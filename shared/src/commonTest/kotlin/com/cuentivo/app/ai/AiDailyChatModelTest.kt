package com.cuentivo.app.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiDailyChatModelTest {

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
        assertFailsWith<IllegalArgumentException> {
            AiDailyChatModel.cutoffDayIso("2026-08-23", retainDays = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AiDailyChatModel.cutoffDayIso("2026-08-23", retainDays = -3)
        }
    }

    @Test
    fun `cutoff rejects malformed iso dates`() {
        assertFailsWith<IllegalArgumentException> {
            AiDailyChatModel.cutoffDayIso("23/08/2026", retainDays = 30)
        }
        assertFailsWith<IllegalArgumentException> {
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
