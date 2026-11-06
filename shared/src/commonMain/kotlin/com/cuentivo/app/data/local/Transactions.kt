package com.cuentivo.app.data.local

/**
 * Ejecuta [block] dentro de una transacción de escritura.
 *
 * Existe porque la forma de abrir una transacción no es la misma en las dos plataformas y el resto
 * del código —la fusión remota y el motor de sync— sí es común:
 *
 * - Android: `withTransaction`, que es lo que ya usaba `RemoteMerger`. Sin cambios.
 * - iOS: no hay `withTransaction` en Room KMP; su equivalente documentado es
 *   `useWriterConnection { it.immediateTransaction { … } }`, y `immediateTransaction` es justo la
 *   variante recomendada para escrituras (toma el bloqueo al empezar, con WAL los lectores siguen).
 *
 * Quien esté dentro del bloque usa los DAOs normales: participan de la transacción porque corren en
 * la misma conexión de escritura que abre [inTransaction].
 */
expect suspend fun <R> CuentivoDatabase.inTransaction(block: suspend () -> R): R
