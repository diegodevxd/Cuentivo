package com.cuentivo.app.domain

sealed interface LedgerBatchConfirmation {
    data class Recorded(val operationsCount: Int) : LedgerBatchConfirmation
    data class AlreadyRecorded(val operationsCount: Int) : LedgerBatchConfirmation
    data object Failed : LedgerBatchConfirmation
}
