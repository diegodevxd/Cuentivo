package com.cuentivo.app.domain

enum class SalePaymentStatus { PENDING, PARTIALLY_PAID, PAID, OVERDUE, CANCELED }

object SaleStatusCalculator {
    fun status(totalCents: Long, paidCents: Long, dueDateEpochDay: Long?, todayEpochDay: Long): SalePaymentStatus {
        require(totalCents >= 0)
        require(paidCents >= 0)
        return when {
            paidCents >= totalCents -> SalePaymentStatus.PAID
            dueDateEpochDay != null && dueDateEpochDay < todayEpochDay -> SalePaymentStatus.OVERDUE
            paidCents > 0 -> SalePaymentStatus.PARTIALLY_PAID
            else -> SalePaymentStatus.PENDING
        }
    }
}
