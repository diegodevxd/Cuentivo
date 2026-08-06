package com.cuentivo.app.ui.customer

import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.CancelablePaymentRow
import com.cuentivo.app.domain.DueSale
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerDirectoryPresentationTest {
    @Test
    fun payment_mapping_groups_and_tiebreaks_deterministically() {
        val rows = listOf(
            CancelablePaymentRow("z", "b", "c", 100, "CASH", 20, "ref-z", 10, true),
            CancelablePaymentRow("a", "b", "c", 100, "CASH", 20, "ref-a", 10, false),
            CancelablePaymentRow("other", "b", "c", 200, "TRANSFER", 20, null, 1, false),
        )

        val result = CorrectionCandidatePresentation.paymentCandidates(rows)

        assertEquals(listOf("other", "a", "z"), result.map { it.id })
        assertEquals(listOf(1, 1, 2), result.map { it.ordinal })
        assertEquals(listOf(1, 2, 2), result.map { it.groupSize })
        assertEquals(true, result[2].blockedByCanceledSale)
        assertEquals("ref-a", result[1].reference)
        assertEquals("TRANSFER", result[0].paymentMethod)
    }
    @Test
    fun a_settled_customer_is_never_reported_as_late() {
        // A stale due row must not outvote a zero balance: the ledger already says it is paid.
        val status = CustomerDirectoryPresentation.status(
            customer = balance("ana", "Ana López", 0),
            dueSales = listOf(DueSale("s1", "ana", "Ana López", 90, 5_000)),
            todayEpochDay = 100,
        )

        assertEquals(CustomerTone.SETTLED, status.tone)
        assertEquals(null, status.overdueDays)
    }

    @Test
    fun overdue_counts_days_from_the_oldest_unpaid_due_date() {
        val status = CustomerDirectoryPresentation.status(
            customer = balance("juan", "Juan Pérez", 480_050),
            dueSales = listOf(
                DueSale("s1", "juan", "Juan Pérez", 95, 100_000),
                DueSale("s2", "juan", "Juan Pérez", 98, 380_050),
            ),
            todayEpochDay = 100,
        )

        assertEquals(CustomerTone.OVERDUE, status.tone)
        assertEquals(5L, status.overdueDays)
    }

    @Test
    fun one_day_late_uses_the_singular() {
        val status = CustomerDirectoryPresentation.status(
            customer = balance("juan", "Juan Pérez", 1_000),
            dueSales = listOf(DueSale("s1", "juan", "Juan Pérez", 99, 1_000)),
            todayEpochDay = 100,
        )

        assertEquals(CustomerTone.OVERDUE, status.tone)
        assertEquals(1L, status.overdueDays)
    }

    @Test
    fun a_future_due_date_reports_the_countdown_not_a_late_warning() {
        val status = CustomerDirectoryPresentation.status(
            customer = balance("maria", "María Álvarez", 125_000),
            dueSales = listOf(DueSale("s1", "maria", "María Álvarez", 115, 125_000)),
            todayEpochDay = 100,
        )

        assertEquals(CustomerTone.PENDING, status.tone)
        assertEquals(15L, status.daysUntilDue)
    }

    @Test
    fun a_debt_with_no_dated_sale_still_reads_as_pending() {
        val status = CustomerDirectoryPresentation.status(
            customer = balance("laura", "Laura Restrepo", 85_000),
            dueSales = emptyList(),
            todayEpochDay = 100,
        )

        assertEquals(CustomerTone.PENDING, status.tone)
        assertEquals(null, status.daysUntilDue)
    }

    @Test
    fun search_matches_name_case_insensitively_and_phone_digits() {
        val all = listOf(balance("ana", "Ana López", 100), balance("juan", "Juan Pérez", 200))

        assertEquals(listOf("ana"), filtered(all, query = "ANA").map { it.customer.id })
        assertEquals(listOf("juan"), filtered(all, query = "5512").map { it.customer.id })
    }

    @Test
    fun chips_narrow_to_owing_and_to_late() {
        val all = listOf(
            balance("ana", "Ana López", 0),
            balance("juan", "Juan Pérez", 200),
            balance("maria", "María Álvarez", 300),
        )
        val due = listOf(DueSale("s1", "juan", "Juan Pérez", 95, 200))

        assertEquals(
            listOf("juan", "maria"),
            filtered(all, chip = CustomerFilter.OWING).map { it.customer.id },
        )
        assertEquals(
            listOf("juan"),
            filtered(all, chip = CustomerFilter.OVERDUE, dueSales = due).map { it.customer.id },
        )
    }

    @Test
    fun initials_use_two_words_when_available() {
        // Accents are kept rather than folded: the tile shows the customer's own name back to them.
        assertEquals("MÁ", CustomerDirectoryPresentation.initials("María Álvarez"))
        assertEquals("JP", CustomerDirectoryPresentation.initials("  Juan   Pérez "))
        assertEquals("BO", CustomerDirectoryPresentation.initials("Bodega"))
        assertEquals("?", CustomerDirectoryPresentation.initials("   "))
    }

    private fun filtered(
        customers: List<CustomerBalanceRow>,
        query: String = "",
        chip: CustomerFilter = CustomerFilter.ALL,
        dueSales: List<DueSale> = emptyList(),
    ) = CustomerDirectoryPresentation.filter(customers, query, chip, dueSales, todayEpochDay = 100)

    private fun balance(id: String, name: String, cents: Long) = CustomerBalanceRow(
        customer = CustomerEntity(
            id = id,
            businessId = "business-1",
            name = name,
            normalizedName = name.lowercase(),
            phone = if (id == "juan") "5512345678" else null,
            email = null,
            address = null,
            notes = null,
            creditLimitCents = null,
            isActive = true,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            deletedAtEpochMillis = null,
        ),
        balanceCents = cents,
    )
}
