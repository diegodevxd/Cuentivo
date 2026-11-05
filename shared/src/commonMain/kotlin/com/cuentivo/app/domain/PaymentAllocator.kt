package com.cuentivo.app.domain

data class OutstandingSale(
    val id: String,
    val outstandingCents: Long,
    val dueDateEpochDay: Long?,
    val saleDateEpochDay: Long,
)

data class PaymentAllocation(
    val saleId: String,
    val amountCents: Long,
)

data class PaymentAllocationResult(
    val allocations: List<PaymentAllocation>,
    val creditCents: Long,
)

object PaymentAllocator {
    fun allocate(paymentCents: Long, sales: Iterable<OutstandingSale>): PaymentAllocationResult {
        require(paymentCents > 0) { "Payment must be positive cents." }

        var remainingCents = paymentCents
        val allocations = sales
            .filter { it.outstandingCents > 0 }
            .sortedWith(compareBy<OutstandingSale> { it.dueDateEpochDay ?: Long.MAX_VALUE }
                .thenBy { it.saleDateEpochDay }
                .thenBy { it.id })
            .mapNotNull { sale ->
                if (remainingCents == 0L) return@mapNotNull null
                val allocatedCents = minOf(remainingCents, sale.outstandingCents)
                remainingCents -= allocatedCents
                PaymentAllocation(sale.id, allocatedCents)
            }

        return PaymentAllocationResult(allocations, creditCents = remainingCents)
    }
}
