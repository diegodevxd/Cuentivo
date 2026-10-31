package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAlertsTest {

    private val today = 20_000L

    private fun sale(name: String, dueIn: Long, cents: Long = 10_000) = DueSale(
        saleId = "sale-$name-$dueIn",
        customerId = "customer-$name",
        customerName = name,
        dueDateEpochDay = today + dueIn,
        outstandingCents = cents,
    )

    private fun alerts(vararg sales: DueSale, customers: Int = 0) =
        AppAlerts.from(sales.toList(), today, SubscriptionTier.PRO, customers)

    @Test
    fun `nothing due and nothing late means no alerts`() {
        assertEquals(emptyList<Alert>(), alerts())
    }

    @Test
    fun `a sale due next month is not an alert`() {
        // Showing it would bury the ones that actually need a phone call today.
        assertEquals(emptyList<Alert>(), alerts(sale("Ana", dueIn = 30)))
    }

    @Test
    fun `today, tomorrow and late are each worded differently`() {
        val result = alerts(sale("Hoy", 0), sale("Manana", 1), sale("Tarde", -3))

        assertEquals(3, result.size)
        assertTrue(result.first { it.title == "Hoy" }.detail.contains("hoy"))
        assertTrue(result.first { it.title == "Manana" }.detail.contains("mañana"))
        assertTrue(result.first { it.title == "Tarde" }.detail.contains("3 días"))
    }

    @Test
    fun `late comes first, and the longest wait leads`() {
        val result = alerts(sale("Hoy", 0), sale("PocoTarde", -2), sale("MuyTarde", -9))

        assertEquals(listOf("MuyTarde", "PocoTarde", "Hoy"), result.map { it.title })
        assertEquals(Alert.Severity.OVERDUE, result.first().severity)
    }

    @Test
    fun `one day late says day, not days`() {
        val result = alerts(sale("Beto", -1))

        assertTrue(result.first().detail.contains("1 día"))
        assertTrue(!result.first().detail.contains("1 días"))
    }

    @Test
    fun `an alert carries the customer so the screen can lead somewhere`() {
        assertEquals("customer-Ana", alerts(sale("Ana", -1)).first().customerId)
    }

    @Test
    fun `the free plan warns one customer before the wall`() {
        val result = AppAlerts.from(emptyList(), today, SubscriptionTier.FREE, activeCustomers = 4)

        assertEquals(1, result.size)
        assertEquals(Alert.Severity.INFO, result.first().severity)
        assertTrue(result.first().title.contains("1 cliente"))
    }

    @Test
    fun `the free plan says so once the limit is reached`() {
        val result = AppAlerts.from(emptyList(), today, SubscriptionTier.FREE, activeCustomers = 5)

        assertTrue(result.first().title.contains("límite"))
    }

    @Test
    fun `pro is never nagged about limits it does not have`() {
        assertEquals(emptyList<Alert>(), AppAlerts.from(emptyList(), today, SubscriptionTier.PRO, 500))
    }

    @Test
    fun `plan notices come after the money, never before it`() {
        val result = AppAlerts.from(listOf(sale("Ana", -5)), today, SubscriptionTier.FREE, 5)

        assertEquals(Alert.Severity.OVERDUE, result.first().severity)
        assertEquals(Alert.Severity.INFO, result.last().severity)
    }
}
