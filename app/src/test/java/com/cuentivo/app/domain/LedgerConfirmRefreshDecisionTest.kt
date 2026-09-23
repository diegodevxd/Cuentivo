package com.cuentivo.app.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `resultado no nulo refresca - Recorded, AlreadyRecorded y Failed devueltos con normalidad`() {
        assertTrue(
            "una escritura confirmada debe recargar las pantallas",
            LedgerConfirmRefreshDecision.shouldRefresh(LedgerBatchConfirmation.Recorded(3)),
        )
        assertTrue(
            "un lote ya escrito también: el commit existe y la pantalla no lo muestra",
            LedgerConfirmRefreshDecision.shouldRefresh(LedgerBatchConfirmation.AlreadyRecorded(3)),
        )
        assertTrue(
            "Failed es un resultado NO nulo (el encargo manda refrescar tras resultado no nulo); " +
                "recargar es inocuo y no perder ningún caso de éxito",
            LedgerConfirmRefreshDecision.shouldRefresh(LedgerBatchConfirmation.Failed),
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
        assertEquals("una escritura confirmada tiene que refrescar", 1, refreshed)
    }

    @Test
    fun `tras AlreadyRecorded se llama onRefresh`() = runBlocking {
        var refreshed = 0
        LedgerConfirmAndRefresh.confirmAndRefresh(
            confirm = { LedgerBatchConfirmation.AlreadyRecorded(3) },
            onRefresh = { refreshed++ },
        )
        assertEquals("un commit cuyo refresco se perdió también refresca", 1, refreshed)
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
        assertTrue("la excepción se propaga", thrown.isFailure)
        assertEquals("sin escritura no hay nada que refrescar", 0, refreshed)
    }
}
