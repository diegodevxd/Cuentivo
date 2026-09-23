package com.cuentivo.app.domain

/**
 * ¿Hay que recargar las pantallas que leen de Room después de confirmar una página de libreta?
 *
 * Vive aquí y no dentro de `MainActivity` porque la decisión es la que importa: el callback de
 * confirmación corre en `Dispatchers.IO` (`AiDebugLabScreen.kt` envuelve la llamada en
 * `withContext(Dispatchers.IO)`), la UI no se puede probar desde JVM, y una decisión incrustada
 * en el composable sólo se podría verificar leyendo su fuente. Saca la regla a un objeto puro y
 * la prueba la invoca de verdad (regla de CLAUDE.md: extraer, no copiar).
 *
 * El criterio es literalmente el del encargo v44: **resultado no nulo → refrescar**.
 * [LedgerBatchCoordinator.confirm] sólo devuelve `null` en el sentido de que puede lanzar — y si
 * lanza, este código ni se alcanza (la excepción se propaga y la pantalla la convierte en `null`
 * con `runCatching { … }.getOrNull()`), así que también queda sin refrescar, que es lo correcto:
 * si la confirmación revienta no hubo escritura que mostrar. Un `Failed` devuelto con normalidad
 * SÍ refresca (es no nulo): recargar es inocuo y el caso que importa — `Recorded` después de un
 * proceso que se cerró antes de pintar — queda cubierto, junto con `AlreadyRecorded`, que es
 * exactamente un commit cuyo refresco se perdió.
 */
object LedgerConfirmRefreshDecision {
    fun shouldRefresh(result: LedgerBatchConfirmation?): Boolean = result != null
}
