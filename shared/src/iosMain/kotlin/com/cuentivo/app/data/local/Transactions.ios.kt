package com.cuentivo.app.data.local

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * iOS: la forma que documenta Room KMP para el equivalente de `withTransaction`.
 *
 * > “The equivalent of the Android-only `withTransaction{ … }` API is the
 * > `useWriterConnection{ it.immediateTransaction{ … } }`.”
 *
 * `immediateTransaction` es la variante recomendada para escrituras: toma el bloqueo al empezar y,
 * en modo WAL (el de por defecto), los lectores siguen leyendo. Los DAOs llamados dentro participan
 * porque corren sobre la misma conexión de escritura.
 */
actual suspend fun <R> CuentivoDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
