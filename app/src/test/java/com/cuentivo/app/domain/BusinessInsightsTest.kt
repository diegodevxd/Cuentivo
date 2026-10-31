package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessInsightsTest {

    private fun totals(collected: Long = 0, sold: Long = 0, overdue: Long = 0) =
        PeriodTotals(soldCents = sold, collectedCents = collected, overdueCents = overdue)

    @Test
    fun `a quiet month on both sides says nothing at all`() {
        // A card announcing "0% change" to someone who has not sold anything is noise.
        assertEquals(emptyList<Insight>(), BusinessInsights.compare(totals(), totals()))
    }

    @Test
    fun `the first month with activity does not invent a percentage`() {
        val insights = BusinessInsights.compare(totals(collected = 50_000), totals())

        assertEquals(1, insights.size)
        assertEquals(Insight.Tone.NEUTRAL, insights.first().tone)
        // No division by zero, and no "subió 100%" conjured out of nothing.
        assertTrue(!insights.first().text.contains("%"))
    }

    @Test
    fun `a real improvement is reported with the real number`() {
        // 123.00 collected against 100.00 last month.
        val insights = BusinessInsights.compare(totals(collected = 12_300), totals(collected = 10_000))

        assertEquals(Insight.Tone.GOOD, insights.first().tone)
        assertTrue(insights.first().text.contains("23%"))
    }

    @Test
    fun `a drop is reported as a drop, not softened`() {
        val insights = BusinessInsights.compare(totals(collected = 8_200), totals(collected = 10_000))

        assertEquals(Insight.Tone.BAD, insights.first().tone)
        // Shown as a positive 18, worded as a decrease — never "-18%".
        assertTrue(insights.first().text.contains("18%"))
        assertTrue(insights.first().text.contains("bajó"))
    }

    @Test
    fun `a change too small to matter is called even`() {
        val insights = BusinessInsights.compare(totals(collected = 10_200), totals(collected = 10_000))

        assertEquals(Insight.Tone.NEUTRAL, insights.first().tone)
        assertTrue(insights.first().text.contains("parejo"))
    }

    @Test
    fun `overdue money always gets its own warning`() {
        val insights = BusinessInsights.compare(
            totals(collected = 12_300, overdue = 45_000),
            totals(collected = 10_000),
        )

        assertEquals(2, insights.size)
        val overdue = insights.last()
        assertEquals(Insight.Tone.BAD, overdue.tone)
        assertTrue(overdue.text.contains(Money.format(45_000)))
    }

    @Test
    fun `no overdue money means no warning`() {
        val insights = BusinessInsights.compare(totals(collected = 12_300), totals(collected = 10_000))

        assertEquals(1, insights.size)
    }
}
