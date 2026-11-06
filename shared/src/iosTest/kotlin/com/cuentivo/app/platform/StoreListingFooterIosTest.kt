package com.cuentivo.app.platform

import com.cuentivo.app.domain.DebtorShareText
import com.cuentivo.app.domain.SubscriptionTier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El pie en iOS: sin Google Play.
 *
 * Decirle "Gratis en Google Play" a un cliente desde la app de iOS es falso y es motivo de rechazo en
 * la App Store; el enlace de la App Store no existe hasta el Día de pago, así que el pie termina en
 * la firma.
 *
 * Pendiente de CI: esta prueba sólo corre en `:shared:iosSimulatorArm64Test`.
 */
class StoreListingFooterIosTest {

    @Test
    fun `la url de la tienda no existe todavia`() {
        assertNull(StoreListing.url)
        assertFalse(StoreListing.footerLine.contains("Google"))
        assertFalse(StoreListing.footerLine.contains("play.google"))
    }

    @Test
    fun `el mensaje al cliente no menciona Google Play`() {
        val text = DebtorShareText.forCustomer(
            tier = SubscriptionTier.FREE,
            businessName = "Abarrotes Don Luis",
            customerName = "Rosa",
            balanceCents = 12_500,
        )

        assertTrue(text.contains("Enviado con Cuentivo"))
        assertFalse(text.contains("Google"))
        assertFalse(text.contains("play.google"))
    }
}
