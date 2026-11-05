package com.cuentivo.app.domain

enum class MovementType {
    CHARGE,
    PAYMENT,
    CANCELLATION,
    REFUND,
    ADJUSTMENT_DEBIT,
    ADJUSTMENT_CREDIT,
}

data class AccountMovement(
    val type: MovementType,
    val amountCents: Long,
) {
    init {
        require(amountCents > 0) { "Movement amounts must be positive cents." }
    }
}

data class SaleItem(
    val quantity: Long,
    val unitPriceCents: Long,
) {
    init {
        require(quantity > 0) { "Sale item quantity must be positive." }
        require(unitPriceCents >= 0) { "Sale item price cannot be negative." }
    }
}

object FinancialCalculator {
    fun customerBalance(movements: Iterable<AccountMovement>): Long =
        movements.sumOf { movement ->
            when (movement.type) {
                MovementType.CHARGE -> movement.amountCents
                MovementType.PAYMENT -> -movement.amountCents
                MovementType.CANCELLATION -> -movement.amountCents
                MovementType.REFUND -> movement.amountCents
                MovementType.ADJUSTMENT_DEBIT -> movement.amountCents
                MovementType.ADJUSTMENT_CREDIT -> -movement.amountCents
            }
        }

    fun saleTotal(items: Iterable<SaleItem>, discountCents: Long): Long {
        require(discountCents >= 0) { "Discount cannot be negative." }
        val subtotal = items.sumOf { it.quantity * it.unitPriceCents }
        require(discountCents <= subtotal) { "Discount cannot exceed subtotal." }
        return subtotal - discountCents
    }
}
