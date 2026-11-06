package com.cuentivo.app.platform

import com.cuentivo.app.domain.DebtorShareText
import com.cuentivo.app.domain.SubscriptionTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El pie del mensaje en Android, con el literal de siempre.
 *
 * La comparación con el texto exacto vive aquí y no en `commonTest` porque iOS no lo cumple a
 * propósito: allí no se puede decir "Gratis en Google Play".
 */
class StoreListingFooterTest {

    private val playUrl = "https://play.google.com/store/apps/details?id=com.cuentivo.app"
    private val footer = "\n\nEnviado con Cuentivo · Gratis en Google Play\n$playUrl"

    @Test
    fun `la firma y la url son las de siempre`() {
        assertEquals("Enviado con Cuentivo · Gratis en Google Play", StoreListing.footerLine)
        assertEquals(playUrl, StoreListing.url)
    }

    @Test
    fun `el mensaje al cliente termina con ese pie en el plan gratuito`() {
        val text = DebtorShareText.forCustomer(
            tier = SubscriptionTier.FREE,
            businessName = "Abarrotes Don Luis",
            customerName = "Rosa",
            balanceCents = 12_500,
        )

        assertTrue(text.endsWith(footer), "el mensaje tiene que terminar con el pie de Android")
    }

    @Test
    fun `un plan de pago no lleva pie`() {
        val text = DebtorShareText.forCustomer(
            tier = SubscriptionTier.PRO,
            businessName = "Abarrotes Don Luis",
            customerName = "Rosa",
            balanceCents = 12_500,
        )

        assertFalse(text.contains("Google Play"))
    }
}
