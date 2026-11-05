package com.cuentivo.app.domain

import com.cuentivo.app.data.local.CustomerEntity

/** One line of the notebook as it was typed: a name and what that person owes. */
data class OpeningBalanceRow(val name: String, val amountText: String)

/** Why a row cannot be imported. The index is the row on screen, so the person can go fix it. */
data class OpeningBalanceRejection(val index: Int, val reason: Reason) {
    enum class Reason { BLANK_NAME, INVALID_AMOUNT, DUPLICATE_NAME }
}

/**
 * What the import would do. Nothing has been written when this exists.
 *
 * [customers] holds only the people who are **not** in the ledger yet; a row whose name is already
 * there gets its debt attached to the existing customer instead.
 */
data class OpeningBalancePlan(
    val customers: List<CustomerEntity>,
    val entries: List<OpeningBalanceEntry>,
    val rejected: List<OpeningBalanceRejection>,
) {
    val totalCents: Long get() = entries.sumOf { it.sale.totalCents }
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * Plans the "traer mis deudas de la libreta" import. **Plans but never writes**, like
 * [LegacyMigrationPlanner] — the person sees exactly what is about to happen and confirms it.
 *
 * Pure: no clock, no `UUID`, no `Context`. Ids and the instant come from the caller, so the same
 * notebook always plans to the same rows and the tests need no device.
 */
object OpeningBalancePlanner {

    fun plan(
        businessId: String,
        ownerUserId: String,
        rows: List<OpeningBalanceRow>,
        /** Everyone already in this business, so a repeated name lands on the person who exists. */
        existingCustomers: List<CustomerEntity>,
        todayEpochDay: Long,
        nowEpochMillis: Long,
        newId: () -> String,
    ): OpeningBalancePlan {
        require(businessId.isNotBlank() && ownerUserId.isNotBlank())
        // Keyed the same way the unique index on (businessId, normalizedName) is, so "José" and
        // "jose" resolve to one person here exactly as they would when the insert runs.
        val known = existingCustomers.associateBy { CustomerNameNormalizer.key(it.name) }.toMutableMap()
        val seenInBatch = mutableSetOf<String>()
        val created = mutableListOf<CustomerEntity>()
        val entries = mutableListOf<OpeningBalanceEntry>()
        val rejected = mutableListOf<OpeningBalanceRejection>()

        rows.forEachIndexed { index, row ->
            val name = row.name.trim()
            if (name.isEmpty()) {
                rejected += OpeningBalanceRejection(index, OpeningBalanceRejection.Reason.BLANK_NAME)
                return@forEachIndexed
            }
            val amountCents = Money.positiveCentsFromPesos(row.amountText.trim())
            if (amountCents == null) {
                rejected += OpeningBalanceRejection(index, OpeningBalanceRejection.Reason.INVALID_AMOUNT)
                return@forEachIndexed
            }
            val key = CustomerNameNormalizer.key(name)
            // Two rows for the same person is almost always the same debt written twice, and this
            // import cannot be undone: nothing financial is edited or deleted. Better to make them
            // merge the rows themselves than to silently charge somebody twice.
            if (!seenInBatch.add(key)) {
                rejected += OpeningBalanceRejection(index, OpeningBalanceRejection.Reason.DUPLICATE_NAME)
                return@forEachIndexed
            }
            val customer = known.getOrPut(key) {
                CustomerEntity(
                    id = newId(),
                    businessId = businessId,
                    name = name,
                    normalizedName = key,
                    phone = null,
                    email = null,
                    address = null,
                    notes = null,
                    creditLimitCents = null,
                    isActive = true,
                    createdAtEpochMillis = nowEpochMillis,
                    updatedAtEpochMillis = nowEpochMillis,
                    deletedAtEpochMillis = null,
                ).also { created += it }
            }
            entries += OpeningBalance.entry(
                saleId = newId(),
                itemId = newId(),
                chargeId = newId(),
                businessId = businessId,
                customerId = customer.id,
                ownerUserId = ownerUserId,
                amountCents = amountCents,
                todayEpochDay = todayEpochDay,
                nowEpochMillis = nowEpochMillis,
            )
        }
        return OpeningBalancePlan(created, entries, rejected)
    }
}
