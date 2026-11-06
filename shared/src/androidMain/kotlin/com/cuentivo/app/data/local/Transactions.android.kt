package com.cuentivo.app.data.local

import androidx.room.withTransaction

/**
 * Android: exactamente `withTransaction`, que es lo que usaba `RemoteMerger` antes de moverse a
 * común. Ningún cambio de comportamiento.
 */
actual suspend fun <R> CuentivoDatabase.inTransaction(block: suspend () -> R): R =
    withTransaction(block)
