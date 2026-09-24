package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La decisión de crear el negocio solo (v46).
 *
 * La prueba importa por lo que **niega**: crear durante la consulta del claim, o después de que
 * falló, es cómo una cuenta terminó con varios negocios. Cada rama de [BusinessClaim] está cubierta.
 */
class DefaultBusinessCreationPolicyTest {

    @Test
    fun `con la nube respondiendo que no hay negocio, se crea`() {
        assertEquals(
            DefaultBusinessDecision.Create,
            DefaultBusinessCreationPolicy.decide(
                claim = BusinessClaim.CreateNew,
                hasLocalBusiness = false,
                waitingForEntitlement = false,
            ),
        )
    }

    @Test
    fun `mientras el claim consulta la nube todavia no se crea`() {
        assertEquals(
            DefaultBusinessDecision.Wait,
            DefaultBusinessCreationPolicy.decide(
                claim = null,
                hasLocalBusiness = false,
                waitingForEntitlement = false,
            ),
        )
    }

    @Test
    fun `un claim bloqueado no crea nunca, ni aunque la consulta haya terminado`() {
        assertEquals(
            DefaultBusinessDecision.DoNothing,
            DefaultBusinessCreationPolicy.decide(
                claim = BusinessClaim.Blocked,
                hasLocalBusiness = false,
                waitingForEntitlement = false,
            ),
        )
    }

    @Test
    fun `un negocio remoto adoptado no crea un segundo negocio`() {
        assertEquals(
            DefaultBusinessDecision.DoNothing,
            DefaultBusinessCreationPolicy.decide(
                claim = BusinessClaim.AdoptRemote("business-remoto"),
                hasLocalBusiness = false,
                waitingForEntitlement = false,
            ),
        )
    }

    @Test
    fun `ya tener negocio local es idempotente, sin importar el claim`() {
        listOf<BusinessClaim?>(
            null,
            BusinessClaim.UseLocal,
            BusinessClaim.CreateNew,
            BusinessClaim.AdoptRemote("b"),
            BusinessClaim.Blocked,
        ).forEach { claim ->
            assertEquals(
                "con negocio local no se crea otro (claim=$claim)",
                DefaultBusinessDecision.DoNothing,
                DefaultBusinessCreationPolicy.decide(
                    claim = claim,
                    hasLocalBusiness = true,
                    waitingForEntitlement = false,
                ),
            )
        }
    }

    @Test
    fun `un telefono vacio espera a que Play responda antes de crear`() {
        assertEquals(
            DefaultBusinessDecision.Wait,
            DefaultBusinessCreationPolicy.decide(
                claim = BusinessClaim.CreateNew,
                hasLocalBusiness = false,
                waitingForEntitlement = true,
            ),
        )
    }

    @Test
    fun `UseLocal sin fila local no crea - el claim manda sobre la heuristica`() {
        // No debería ocurrir en producción (UseLocal implica fila), pero la política no puede
        // convertirlo en una creación silenciosa si algún día la fila desaparece entre lecturas.
        assertEquals(
            DefaultBusinessDecision.DoNothing,
            DefaultBusinessCreationPolicy.decide(
                claim = BusinessClaim.UseLocal,
                hasLocalBusiness = false,
                waitingForEntitlement = false,
            ),
        )
    }
}
