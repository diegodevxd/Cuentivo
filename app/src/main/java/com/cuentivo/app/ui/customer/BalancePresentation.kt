package com.cuentivo.app.ui.customer

internal enum class BalanceKind { OWED, SETTLED, CUSTOMER_CREDIT }

internal data class BalancePresentation(
    val kind: BalanceKind,
    val amountCents: Long,
)

internal object BalancePresenter {
    fun from(balanceCents: Long): BalancePresentation {
        require(balanceCents != Long.MIN_VALUE) {
            "balanceCents must not be Long.MIN_VALUE because its magnitude cannot be represented as a positive Long"
        }

        return when {
            balanceCents > 0L -> BalancePresentation(BalanceKind.OWED, balanceCents)
            balanceCents < 0L -> BalancePresentation(BalanceKind.CUSTOMER_CREDIT, -balanceCents)
            else -> BalancePresentation(BalanceKind.SETTLED, 0L)
        }
    }
}
