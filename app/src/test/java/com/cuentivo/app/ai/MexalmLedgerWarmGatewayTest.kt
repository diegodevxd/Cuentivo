package com.cuentivo.app.ai

import com.cuentivo.app.domain.SubscriptionTier
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El despertar del Ledger de MexaLM al abrir la app.
 *
 * El Ledger es un Cloud Run con `min-instances=0`: se apaga solo y tarda entre 30 y 60 s en
 * volver. Despertarlo por adelantado es un favor, y un favor no puede romper nada: por eso las
 * reglas de aquí son cortas y cerradas.
 */
class MexalmLedgerWarmGatewayTest {

    @Test
    fun `el despertar es solo para quien tiene CuentivoAI`() {
        assertTrue(MexalmLedgerWarmPolicy.shouldWarm(SubscriptionTier.AI))
        assertFalse(MexalmLedgerWarmPolicy.shouldWarm(SubscriptionTier.PRO))
        assertFalse(MexalmLedgerWarmPolicy.shouldWarm(SubscriptionTier.FREE))
    }

    @Test
    fun `el transporte no bloquea la UI y no propaga ningun error`() {
        val source = File("src/main/java/com/cuentivo/app/ai/MexalmLedgerWarmGateway.kt").readText()

        // El bloqueo de la llamada vive fuera del hilo principal.
        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("Tasks.await(task)"))
        // Y ningún error sale: un Ledger caído, una red caída o una callable que todavía no
        // existe son lo mismo que no haber despertado nada.
        assertTrue(source.contains("catch (_: Exception)"))
    }

    @Test
    fun `la app pide el despertar al volver a primer plano`() {
        val source = File("src/main/java/com/cuentivo/app/ui/CuentivoApp.kt").readText()

        // El despertar va con el evento de volver a primer plano, que es cuando el Ledger se
        // duerme.
        assertTrue(source.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(source.contains("warmMexalmLedger()"))
        // Y sólo si la cuenta tiene CuentivoAI: la regla vive en el objeto puro, no aquí.
        assertTrue(source.contains("MexalmLedgerWarmPolicy.shouldWarm(tier)"))
    }
}
