package com.cuentivo.app.ui.customer

import com.cuentivo.app.data.local.CancelablePaymentRow
import com.cuentivo.app.ui.CorrectionCandidate

/** Pure, stable mapping for correction rows. The database remains the source of truth. */
internal object CorrectionCandidatePresentation {
    fun paymentCandidates(rows: List<CancelablePaymentRow>): List<CorrectionCandidate.Payment> {
        val groups = rows.groupBy { it.amountCents to it.paymentDateEpochDay }
            .mapValues { (_, group) -> group.sortedWith(compareBy({ it.createdAtEpochMillis }, { it.id })) }
        return rows.sortedWith(compareBy({ it.paymentDateEpochDay }, { it.createdAtEpochMillis }, { it.id }))
            .map { row ->
                val group = groups.getValue(row.amountCents to row.paymentDateEpochDay)
                CorrectionCandidate.Payment(
                    id = row.id,
                    amountCents = row.amountCents,
                    paymentDateEpochDay = row.paymentDateEpochDay,
                    createdAtEpochMillis = row.createdAtEpochMillis,
                    paymentMethod = row.paymentMethod,
                    reference = row.reference,
                    ordinal = group.indexOfFirst { it.id == row.id } + 1,
                    groupSize = group.size,
                    blockedByCanceledSale = row.blockedByCanceledSale,
                )
            }
    }
}
