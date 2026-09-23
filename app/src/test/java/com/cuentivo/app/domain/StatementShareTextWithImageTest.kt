package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E · Con imagen, el texto va corto (v44).
 *
 * WhatsApp manda imagen y texto juntos —el texto queda como pie de foto—, así que los movimientos
 * salían dos veces: en la imagen y en el texto. Decidido por Diego (2026-09-22): cuando va con
 * imagen, el texto se acorta. Sin movimientos, con el mismo encabezado y el mismo pie que el texto
 * completo, y el mismo saldo en cero / a favor.
 *
 * Y el respaldo: si la imagen falla, se comparte el texto completo de siempre. La elección vive en
 * `StatementShareTextChoice`, puro y probado aquí.
 */
class StatementShareTextWithImageTest {

    private val entries = listOf(
        StatementEntry("2026-08-03", "CHARGE", "DEBIT", 79_900, null, saleId = "s1", detail = "2 Jabón Zote"),
        StatementEntry("2026-08-05", "PAYMENT", "CREDIT", 20_000, null),
    )

    private fun short(
        tier: SubscriptionTier = SubscriptionTier.FREE,
        businessName: String = "Novedades Lupita",
        customerName: String = "Dana TS",
        balanceCents: Long = 59_900,
    ) = DebtorShareText.forCustomerWithImage(tier, businessName, customerName, balanceCents)

    @Test
    fun `con imagen el texto no lleva ninguna linea de movimiento`() {
        val text = short()
        // Ni compras ni abonos: ya van en la imagen.
        assertFalse("no puede haber una compra en el texto corto", text.contains("Compra"))
        assertFalse("ni un abono", text.contains("Abono"))
        assertFalse("ni el detalle del producto", text.contains("Jabón Zote"))
        // Y sí el saldo, que es lo que el pie de foto tiene que decir.
        assertTrue(text.contains("Saldo pendiente: ${Money.format(59_900)}"))
    }

    @Test
    fun `el encabezado del texto corto es el mismo del completo`() {
        // Con nombre de negocio configurado.
        assertEquals(
            "Hola Dana TS, te comparto tu estado de cuenta de Novedades Lupita.",
            short(businessName = "Novedades Lupita").lines().first(),
        )
        // Y sin él: el nombre por defecto se sigue callando.
        for (name in listOf("", "   ", BusinessName.DEFAULT, "  mi NEGOCIO  ")) {
            assertEquals(
                "con '$name' el encabezado tiene que ser corto",
                "Hola Dana TS, te comparto tu estado de cuenta.",
                short(businessName = name).lines().first(),
            )
        }
    }

    @Test
    fun `el pie del texto corto es el mismo del completo`() {
        assertTrue(short(tier = SubscriptionTier.FREE).contains("Enviado con Cuentivo · Gratis en Google Play"))
        for (paid in listOf(SubscriptionTier.PRO, SubscriptionTier.AI)) {
            assertFalse("$paid no lleva pie", short(tier = paid).contains("Enviado con Cuentivo"))
        }
    }

    @Test
    fun `saldo en cero y a favor usan el mismo texto que el completo`() {
        val settled = short(balanceCents = 0)
        assertTrue(settled.contains("al corriente"))
        assertFalse(settled.contains("Saldo pendiente"))

        // A favor (saldo negativo) también es "al corriente": no se le pide dinero a quien no debe.
        val inCredit = short(balanceCents = -5_000)
        assertTrue(inCredit.contains("al corriente"))
        assertFalse(inCredit.contains("Saldo pendiente"))
    }

    @Test
    fun `el texto corto nunca menciona a otro cliente`() {
        val text = short(customerName = "Dana TS", balanceCents = 59_900)
        assertTrue(text.contains("Dana TS"))
        assertFalse("el nombre de otra clienta no puede viajar", text.contains("Beto"))
        assertFalse("ni su saldo", text.contains(Money.format(40_000)))
    }

    @Test
    fun `si la imagen falla se comparte el texto completo con movimientos`() {
        val shortText = short()
        val fullText = DebtorShareText.forCustomer(
            SubscriptionTier.FREE,
            "Novedades Lupita",
            "Dana TS",
            59_900,
            entries,
        )
        // La imagen falló: el texto elegido es el completo, con movimientos.
        val chosen = StatementShareTextChoice.choose(imageSucceeded = false, shortText = shortText, fullText = fullText)
        assertEquals(fullText, chosen)
        assertTrue("el respaldo sí lleva los movimientos", chosen.contains("Compra"))
        assertTrue(chosen.contains("Abono"))
    }

    @Test
    fun `si la imagen va bien se comparte el texto corto`() {
        val shortText = short()
        val fullText = DebtorShareText.forCustomer(
            SubscriptionTier.FREE,
            "Novedades Lupita",
            "Dana TS",
            59_900,
            entries,
        )
        val chosen = StatementShareTextChoice.choose(imageSucceeded = true, shortText = shortText, fullText = fullText)
        assertEquals(shortText, chosen)
        assertFalse("el texto corto no repite los movimientos", chosen.contains("Compra"))
    }
}
