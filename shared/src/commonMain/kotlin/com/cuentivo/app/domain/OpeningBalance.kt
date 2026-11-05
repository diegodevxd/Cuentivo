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

/** What an uncataloged debt or charge sale produces, ready to be written in one transaction. */
data class UncatalogedSaleEntry(
    val sale: SaleEntity,
    val item: SaleItemEntity,
    val charge: AccountMovementEntity,
)

/**
 * Shared factory for single-item uncataloged charge sales (OpeningBalance and AiSaleExecution).
 * Guarantees consistent validation, trimming, 160-char length cap, and entity creation.
 */
object UncatalogedSaleBuilder {
    const val MAX_DESCRIPTION_LENGTH = 160

    fun build(
        saleId: String,
        itemId: String,
        chargeId: String,
        businessId: String,
        customerId: String,
        sellerUserId: String,
        amountCents: Long,
        saleNumber: String,
        saleDateEpochDay: Long,
        todayEpochDay: Long,
        nowEpochMillis: Long,
        dueDateEpochDay: Long? = null,
        notes: String? = null,
        lineNameSnapshot: String,
    ): UncatalogedSaleEntry {
        require(amountCents > 0) { "Amount must be positive." }
        require(saleDateEpochDay > 0) { "Sale date must be positive." }
        require(saleDateEpochDay <= todayEpochDay) { "Sale date cannot be in the future." }
        if (dueDateEpochDay != null) {
            require(dueDateEpochDay >= saleDateEpochDay) {
                "Due date ($dueDateEpochDay) cannot be earlier than sale date ($saleDateEpochDay)."
            }
        }
        val trimmedName = lineNameSnapshot.trim()
        require(trimmedName.isNotBlank()) { "Line name snapshot must not be blank." }
        require(trimmedName.length <= MAX_DESCRIPTION_LENGTH) {
            "Line name snapshot exceeds maximum length of $MAX_DESCRIPTION_LENGTH characters."
        }
        val sale = SaleEntity(
            id = saleId,
            businessId = businessId,
            customerId = customerId,
            sellerUserId = sellerUserId,
            saleNumber = saleNumber,
            saleDateEpochDay = saleDateEpochDay,
            dueDateEpochDay = dueDateEpochDay,
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
            productNameSnapshot = trimmedName,
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
            createdByUserId = sellerUserId,
            reversesMovementId = null,
            notes = notes,
        )
        return UncatalogedSaleEntry(sale, item, charge)
    }
}

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
    const val MAX_DESCRIPTION_LENGTH = UncatalogedSaleBuilder.MAX_DESCRIPTION_LENGTH

    /**
     * Marca persistida que distingue esta venta de una venta real.
     *
     * `FinancialDao` ya clasificaba como `PRIOR_DEBT` lo que llevara este `notes`, pero nada lo
     * escribía: la consulta de clasificación nunca casaba. Se escribe aquí para que el marcador y su
     * lector vuelvan a coincidir, y para que el "Vendido del mes" pueda excluirla —una deuda que ya
     * existía no es una venta del mes (v44). Es texto almacenado, nunca traducido, por la misma razón
     * que [LINE_NAME].
     */
    const val NOTES_MARKER = "ALTA_CLIENTE_SALDO_ANTERIOR"

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
        notes: String? = NOTES_MARKER,
        /**
         * The date of the sale as recorded in the notebook, defaulting to todayEpochDay.
         * Defaulted to today rather than guessed for new customer onboarding: nobody knows when
         * the notebook entry was made, and inventing a date would feed the payment score evidence
         * that was never observed. The parameter exists precisely because a physical notebook or
         * page import does provide an observed date.
         * Must be positive and not in the future (<= todayEpochDay).
         */
        saleDateEpochDay: Long = todayEpochDay,
        /**
         * The description of the debt / uncatalogued line. Defaults to LINE_NAME ("Saldo anterior").
         */
        lineNameSnapshot: String = LINE_NAME,
    ): OpeningBalanceEntry {
        require(dueInDays == null || dueInDays > 0) { "An agreed date cannot be today or earlier." }
        val record = UncatalogedSaleBuilder.build(
            saleId = saleId,
            itemId = itemId,
            chargeId = chargeId,
            businessId = businessId,
            customerId = customerId,
            sellerUserId = ownerUserId,
            amountCents = amountCents,
            saleNumber = "SA-$nowEpochMillis",
            saleDateEpochDay = saleDateEpochDay,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
            dueDateEpochDay = dueInDays?.let { todayEpochDay + it },
            notes = notes,
            lineNameSnapshot = lineNameSnapshot,
        )
        return OpeningBalanceEntry(record.sale, record.item, record.charge)
    }
}

