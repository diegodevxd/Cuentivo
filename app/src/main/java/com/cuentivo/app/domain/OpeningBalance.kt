package com.cuentivo.app.domain

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity

/** What a debt carried over from before the app amounts to, ready to be written in one transaction. */
data class OpeningBalanceEntry(
    val sale: SaleEntity,
    val item: SaleItemEntity,
    val charge: AccountMovementEntity,
)

/**
 * Turns "this person already owed me $500" into the same three rows any other sale produces.
 *
 * Recorded as a **sale**, not as an `ADJUSTMENT` movement, even though an adjustment is closer to
 * what it is on paper. Everything that lets a debt actually be collected — allocating a payment
 * against it, the debtor list, the statement, the WhatsApp reminder, the ageing behind the traffic
 * light — hangs off `sales`. An adjustment would show up in the balance and nowhere else, which is
 * exactly the half-working state this is meant to replace.
 *
 * The line carries no `productId`. That is not a workaround: `sale_items.productId` is nullable in
 * Room and in `firestore.rules`, and [LegacyMigrationPlanner] has been writing lines that way since
 * the web import. What it replaces is people inventing a catalog product called "deuda anterior".
 *
 * Pure: no clock, no `UUID`, no `Context`. The caller supplies the ids and the instant, so the same
 * inputs always produce the same rows and the tests need no device.
 */
object OpeningBalance {

    /**
     * Written onto `productNameSnapshot`, which is persisted, synced to Firestore and read back into
     * the CSV and the WhatsApp text. Fixed Spanish, deliberately, for the same reason
     * `AuditAction.summary` is: it is stored data, and translating it at write time would make the
     * ledger read differently depending on which phone recorded the row. The label on the form is
     * what gets translated.
     */
    const val LINE_NAME = "Saldo anterior"

    fun entry(
        saleId: String,
        itemId: String,
        chargeId: String,
        businessId: String,
        customerId: String,
        ownerUserId: String,
        amountCents: Long,
        todayEpochDay: Long,
        nowEpochMillis: Long,
        /** Days from today the two of them agreed on, or null when there was never a date. */
        dueInDays: Long? = null,
        notes: String? = null,
    ): OpeningBalanceEntry {
        require(amountCents > 0) { "An opening balance must be positive." }
        require(dueInDays == null || dueInDays > 0) { "An agreed date cannot be today or earlier." }
        val sale = SaleEntity(
            id = saleId,
            businessId = businessId,
            customerId = customerId,
            sellerUserId = ownerUserId,
            // Its own prefix so this is recognisable in the ledger without joining anything.
            saleNumber = "SA-$nowEpochMillis",
            // Dated today rather than guessed: nobody knows when the notebook entry was made, and
            // inventing a date would feed the payment score evidence that was never observed.
            saleDateEpochDay = todayEpochDay,
            dueDateEpochDay = dueInDays?.let { todayEpochDay + it },
            subtotalCents = amountCents,
            discountCents = 0L,
            totalCents = amountCents,
            isCanceled = false,
            notes = notes,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
        )
        val item = SaleItemEntity(
            id = itemId,
            saleId = saleId,
            productId = null,
            productNameSnapshot = LINE_NAME,
            quantity = 1L,
            unitPriceCents = amountCents,
            discountCents = 0L,
            subtotalCents = amountCents,
        )
        val charge = AccountMovementEntity(
            id = chargeId,
            businessId = businessId,
            customerId = customerId,
            saleId = saleId,
            paymentId = null,
            type = "CHARGE",
            amountCents = amountCents,
            direction = "DEBIT",
            occurredAtEpochMillis = nowEpochMillis,
            createdByUserId = ownerUserId,
            reversesMovementId = null,
            notes = notes,
        )
        return OpeningBalanceEntry(sale, item, charge)
    }
}
