package com.cuentivo.app.domain

/**
 * Confirmar una página de libreta y, si hubo escritura, pedir el refresco de las pantallas (v44).
 *
 * El defecto que cierra: sin el refresco, la hoja quedaba escrita y la pantalla seguía mostrando el
 * estado anterior; la persona, creyendo que no pasó nada, volvía a registrar la misma página (el
 * duplicado de Laura). El callback de confirmación corre en `Dispatchers.IO`, así que el refresco
 * tiene que volver al hilo principal.
 *
 * Vive aquí y no dentro de `MainActivity` porque la **conducta** es la que importa y una decisión
 * incrustada en un composable sólo se podría verificar leyendo su fuente. Esta función recibe
 * `confirm` y `onRefresh` como lambdas, así que una prueba JVM la invoca de verdad y comprueba que
 * tras `Recorded` y `AlreadyRecorded` se llama `onRefresh`, y tras una excepción no. `MainActivity`
 * usa esta misma función.
 */
object LedgerConfirmAndRefresh {

    /**
     * Ejecuta [confirm] y, si devolvió un resultado no nulo, llama a [onRefresh].
     *
     * Si [confirm] **lanza**, la excepción se propaga y [onRefresh] no se llama: no hubo escritura que
     * mostrar. Un `Failed` devuelto con normalidad SÍ refresca (es no nulo): recargar es inocuo y el
     * caso que importa —`Recorded` después de un proceso que se cerró antes de pintar— queda cubierto,
     * junto con `AlreadyRecorded`, que es exactamente un commit cuyo refresco se perdió.
     */
    suspend fun confirmAndRefresh(
        confirm: suspend () -> LedgerBatchConfirmation,
        onRefresh: suspend () -> Unit,
    ): LedgerBatchConfirmation {
        val result = confirm()
        if (LedgerConfirmRefreshDecision.shouldRefresh(result)) {
            onRefresh()
        }
        return result
    }
}
