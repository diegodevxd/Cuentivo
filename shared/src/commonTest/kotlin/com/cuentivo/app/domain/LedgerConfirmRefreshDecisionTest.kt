package com.cuentivo.app.domain

import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * A · Refrescar al registrar (v44).
 *
 * La conducta, no el fuente: `LedgerConfirmAndRefresh.confirmAndRefresh` recibe `confirm` y
 * `onRefresh` como lambdas, así que esta prueba la invoca de verdad y comprueba que tras `Recorded` y
 * `AlreadyRecorded` se llama `onRefresh`, y tras una excepción no. `MainActivity` usa esa misma
 * función, así que lo que aquí se prueba es lo que corre.
 *
 * (Antes esta prueba leía el fuente de `MainActivity` con `File(...).readText().contains(...)`: era la
 * forma 5 de `.claude/agents/test-honesty-reviewer.md` y quedaba verde con el refresco en código
 * inalcanzable. Se borró.)
 */
class LedgerConfirmRefreshDecisionTest {

    @Test
    fun `resultado no nulo refresca - Recorded AlreadyRecorded y Failed devueltos con normalidad`() {
        assertTrue(
            LedgerConfirmRefreshDecision.shouldRefresh(LedgerBatchConfirmation.Recorded(3)),
            "una escritura confirmada debe recargar las pantallas",
        )
        assertTrue(
            LedgerConfirmRefreshDecision.shouldRefresh(LedgerBatchConfirmation.AlreadyRecorded(3)),
            "un lote ya escrito también: el commit existe y la pantalla no lo muestra",
        )
        assertTrue(
            LedgerConfirmRefreshDecision.shouldRefresh(LedgerBatchConfirmation.Failed),
            "Failed es un resultado NO nulo (el encargo manda refrescar tras resultado no nulo); " +
                "recargar es inocuo y no perder ningún caso de éxito",
        )
    }

    @Test
    fun `resultado nulo no refresca`() {
        // La pantalla sólo ve null cuando la confirmación lanzó (runCatching.getOrNull()):
        // una excepción significa que no hubo escritura que mostrar.
        assertFalse(LedgerConfirmRefreshDecision.shouldRefresh(null))
    }

    @Test
    fun `tras Recorded se llama onRefresh`() = runBlocking {
        var refreshed = 0
        val result = LedgerConfirmAndRefresh.confirmAndRefresh(
            confirm = { LedgerBatchConfirmation.Recorded(3) },
            onRefresh = { refreshed++ },
        )
        assertEquals(LedgerBatchConfirmation.Recorded(3), result)
        assertEquals(1, refreshed, "una escritura confirmada tiene que refrescar")
    }

    @Test
    fun `tras AlreadyRecorded se llama onRefresh`() = runBlocking {
        var refreshed = 0
        LedgerConfirmAndRefresh.confirmAndRefresh(
            confirm = { LedgerBatchConfirmation.AlreadyRecorded(3) },
            onRefresh = { refreshed++ },
        )
        assertEquals(1, refreshed, "un commit cuyo refresco se perdió también refresca")
    }

    @Test
    fun `si confirm lanza no se llama onRefresh`() = runBlocking {
        var refreshed = 0
        val thrown = runCatching {
            LedgerConfirmAndRefresh.confirmAndRefresh(
                confirm = { throw IllegalStateException("sin conexión") },
                onRefresh = { refreshed++ },
            )
        }
        assertTrue(thrown.isFailure, "la excepción se propaga")
        assertEquals(0, refreshed, "sin escritura no hay nada que refrescar")
    }
}
