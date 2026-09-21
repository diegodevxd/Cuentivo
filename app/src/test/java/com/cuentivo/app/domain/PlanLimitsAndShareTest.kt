package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanLimitsAndShareTest {

    // Written against the constant rather than a hard-coded 5: tuning the free plan is meant to be a
    // one-line change in FeatureAccessManager, and a test repeating the number defeats that.
    @Test
    fun `free plan allows customers up to the limit`() {
        val lastFreeSlot = FeatureAccessManager.FREE_CUSTOMER_LIMIT - 1
        val decision = FeatureAccessManager.canAddCustomer(SubscriptionTier.FREE, activeCustomers = lastFreeSlot)
        assertTrue(decision is AccessDecision.Allowed)
    }

    @Test
    fun `free plan blocks the customer past the limit and says why`() {
        val decision = FeatureAccessManager.canAddCustomer(
            SubscriptionTier.FREE,
            activeCustomers = FeatureAccessManager.FREE_CUSTOMER_LIMIT,
        )
        assertTrue(decision is AccessDecision.Blocked)
        assertTrue((decision as AccessDecision.Blocked).detail.contains("Pro"))
        // The number offered has to be the number enforced, or the upgrade prompt lies.
        assertTrue(decision.detail.contains("${FeatureAccessManager.FREE_CUSTOMER_LIMIT}"))
    }

    @Test
    fun `the free customer limit is five`() {
        assertEquals(5, FeatureAccessManager.FREE_CUSTOMER_LIMIT)
    }

    @Test
    fun `pro plan has no customer or sale limit`() {
        assertTrue(FeatureAccessManager.canAddCustomer(SubscriptionTier.PRO, 5_000) is AccessDecision.Allowed)
        assertTrue(FeatureAccessManager.canRegisterSale(SubscriptionTier.PRO, 90_000) is AccessDecision.Allowed)
    }

    @Test
    fun `free plan blocks the sale past the limit`() {
        assertTrue(FeatureAccessManager.canRegisterSale(SubscriptionTier.FREE, 30) is AccessDecision.Blocked)
        assertTrue(FeatureAccessManager.canRegisterSale(SubscriptionTier.FREE, 29) is AccessDecision.Allowed)
    }

    @Test
    fun `paid features are closed on free and open on pro`() {
        assertTrue(FeatureAccessManager.canUse(SubscriptionTier.FREE, PaidFeature.CLOUD_BACKUP) is AccessDecision.Blocked)
        assertTrue(FeatureAccessManager.canUse(SubscriptionTier.PRO, PaidFeature.CLOUD_BACKUP) is AccessDecision.Allowed)
    }

    @Test
    fun `registering a payment is never blocked`() {
        assertTrue(FeatureAccessManager.canRegisterPayment() is AccessDecision.Allowed)
    }

    @Test
    fun `an unknown stored plan degrades to free rather than unlocking`() {
        assertEquals(SubscriptionTier.FREE, SubscriptionTier.fromStoredValue("PLATINUM"))
        assertEquals(SubscriptionTier.FREE, SubscriptionTier.fromStoredValue(null))
        assertEquals(SubscriptionTier.PRO, SubscriptionTier.fromStoredValue("PRO"))
    }

    @Test
    fun `business list shows every debtor largest first with the total`() {
        val text = DebtorShareText.forBusiness(
            SubscriptionTier.FREE,
            "Tienda Ejemplo",
            listOf(Debtor("Ana", 15_000), Debtor("Beto", 40_000, isOverdue = true)),
        )
        assertTrue(text.indexOf("Beto") < text.indexOf("Ana"))
        assertTrue(text.contains("(vencido)"))
        assertTrue(text.contains("Total por cobrar"))
    }

    @Test
    fun `business list leaves out customers who owe nothing`() {
        val text = DebtorShareText.forBusiness(SubscriptionTier.FREE, "Tienda", listOf(Debtor("Ana", 0), Debtor("Beto", 100)))
        assertFalse(text.contains("Ana"))
    }

    @Test
    fun `customer message never mentions another customer`() {
        val text = DebtorShareText.forCustomer(SubscriptionTier.FREE, "Tienda", "Ana", 15_000)
        assertTrue(text.contains("Ana"))
        assertFalse(text.contains("Beto"))
        assertTrue(text.contains("Saldo pendiente"))
    }

    @Test
    fun `a settled customer is thanked instead of being asked for money`() {
        val text = DebtorShareText.forCustomer(SubscriptionTier.FREE, "Tienda", "Ana", 0)
        assertTrue(text.contains("al corriente"))
        assertFalse(text.contains("Saldo pendiente"))
    }

    @Test
    fun `customer message can carry that customer's own movements`() {
        val text = DebtorShareText.forCustomer(
            SubscriptionTier.FREE,
            "Tienda",
            "Ana",
            5_000,
            listOf(
                StatementEntry("2026-07-01", "CHARGE", "DEBIT", 15_000, null, saleId = "s1", detail = "2 Jabón Zote"),
                StatementEntry("2026-07-10", "PAYMENT", "CREDIT", 10_000, null),
            ),
        )
        // "Compra", no "Venta": la palabra la lee el cliente, y para él fue una compra.
        assertTrue(text.contains("Compra"))
        assertTrue(text.contains("Abono"))
        // What was bought, and a date a person can read: the message lands on WhatsApp, where
        // "2026-07-01  Compra" tells the customer nothing about their own debt.
        assertTrue(text.contains("2 Jabón Zote"))
        assertTrue(text.contains("01/07/2026"))
        assertFalse(text.contains("2026-07-01"))
    }

    @Test
    fun `money formats as pesos with two decimals`() {
        assertTrue(Money.format(150_50).contains("150.50"))
    }

    @Test
    fun `money refuses a third decimal instead of truncating it`() {
        assertEquals(null, Money.centsFromPesos("10.005"))
        assertEquals(1_000L, Money.centsFromPesos("10"))
        assertEquals(null, Money.positiveCentsFromPesos("0"))
    }

    // ---------------------------------------------------------------------------------------------
    // El mensaje que le llega AL CLIENTE.
    // ---------------------------------------------------------------------------------------------

    /** El mensaje del cliente con lo mínimo, para las pruebas que no van del nombre ni del pie. */
    private fun customerMessage(
        tier: SubscriptionTier = SubscriptionTier.FREE,
        businessName: String = "Novedades Lupita",
        customerName: String = "Dana TS",
        balanceCents: Long = 79_900,
        entries: List<StatementEntry> = listOf(StatementEntry("2026-08-03", "CHARGE", "DEBIT", 79_900, null)),
    ) = DebtorShareText.forCustomer(tier, businessName, customerName, balanceCents, entries)

    @Test
    fun `el_nombre_por_defecto_o_vacio_no_le_llega_al_cliente`() {
        // "con Mi negocio" es un detalle interno. Vacío, sólo espacios, el valor por defecto exacto y el
        // mismo disfrazado con otras mayúsculas y espacios: los cuatro son el mismo caso.
        for (name in listOf("", "   ", BusinessName.DEFAULT, "  mi NEGOCIO  ")) {
            val firstLine = customerMessage(businessName = name).lines().first()
            assertEquals("con '$name' el encabezado tiene que ser corto", "Hola Dana TS, te comparto tu estado de cuenta.", firstLine)
        }
    }

    @Test
    fun `un_nombre_configurado_si_aparece_y_sin_espacios_de_sobra`() {
        val firstLine = customerMessage(businessName = "  Novedades Lupita  ").lines().first()
        assertEquals("Hola Dana TS, te comparto tu estado de cuenta de Novedades Lupita.", firstLine)
    }

    @Test
    fun `una_compra_va_sin_signo_y_un_abono_con_menos`() {
        val text = customerMessage(
            balanceCents = 59_900,
            entries = listOf(
                StatementEntry("2026-08-03", "CHARGE", "DEBIT", 79_900, null),
                StatementEntry("2026-08-05", "PAYMENT", "CREDIT", 20_000, null),
            ),
        )
        val chargeLine = text.lines().first { it.contains("Compra") }
        val paymentLine = text.lines().first { it.contains("Abono") }
        // Una compra de 799 le llegaba como "+$799.00", que desde el lado del cliente se lee como saldo
        // a favor. Es el defecto que esta ronda existe para cerrar.
        assertFalse("una compra nunca lleva '+': $chargeLine", chargeLine.contains("+"))
        assertTrue("un abono baja la deuda y lleva menos: $paymentLine", paymentLine.contains("−"))
        // Y el saldo, sin signo: desde el lado del cliente no es "menos" nada.
        assertTrue(text.contains("Saldo pendiente: ${Money.format(59_900)}"))
    }

    @Test
    fun `los_tipos_que_diego_no_especifico_toman_el_signo_de_la_direccion`() {
        val text = customerMessage(
            entries = listOf(
                StatementEntry("2026-08-03", "REFUND", "DEBIT", 5_000, null),
                StatementEntry("2026-08-04", "CANCELLATION", "CREDIT", 3_000, null),
                StatementEntry("2026-08-05", "ADJUSTMENT", "CREDIT", 1_000, null),
            ),
        )
        assertFalse("lo que sube la deuda va sin signo", text.lines().first { it.contains("Devolución") }.contains("−"))
        assertTrue("lo que la baja lleva menos", text.lines().first { it.contains("Cancelación") }.contains("−"))
        assertTrue("un ajuste tambien lo dice su direccion", text.lines().first { it.contains("Ajuste") }.contains("−"))
    }

    @Test
    fun `solo_compras_y_cuenta_al_corriente`() {
        val onlyCharges = customerMessage(entries = listOf(StatementEntry("2026-08-03", "CHARGE", "DEBIT", 79_900, null)))
        assertTrue(onlyCharges.contains("Compra"))
        assertFalse(onlyCharges.contains("Abono"))

        val settled = customerMessage(balanceCents = 0, entries = emptyList())
        assertTrue(settled.contains("al corriente"))
        assertFalse(settled.contains("Saldo pendiente"))
    }

    @Test
    fun `el_pie_solo_va_en_el_plan_gratis`() {
        assertTrue(customerMessage(tier = SubscriptionTier.FREE).contains("Enviado con Cuentivo · Gratis en Google Play"))
        // Con includesProBenefits y no `== FREE`: un plan de pago nuevo tampoco lleva publicidad.
        for (paid in listOf(SubscriptionTier.PRO, SubscriptionTier.AI)) {
            assertFalse("$paid no lleva pie", customerMessage(tier = paid).contains("Enviado con Cuentivo"))
        }
    }

    @Test
    fun `la_lista_de_deudores_lleva_el_pie_solo_en_gratis_y_va_sin_signos`() {
        val debtors = listOf(Debtor("Ana", 15_000), Debtor("Beto", 40_000))
        val free = DebtorShareText.forBusiness(SubscriptionTier.FREE, "Novedades Lupita", debtors)
        val pro = DebtorShareText.forBusiness(SubscriptionTier.PRO, "Novedades Lupita", debtors)
        assertTrue(free.contains("Enviado con Cuentivo"))
        assertFalse(pro.contains("Enviado con Cuentivo"))
        // Los signos de la lista ya estaban bien y lo compruebo: todos deben, así que ninguno lleva
        // signo (ni "+" ni el menos).
        assertFalse(free.contains("+"))
        assertFalse(free.contains("−"))
    }

    @Test
    fun `el_mensaje_de_un_cliente_nunca_lleva_el_nombre_ni_el_saldo_de_otro`() {
        // Es la razón de que forCustomer y forBusiness sean dos funciones: este texto sale del teléfono
        // del negocio y lo lee un cliente.
        val text = customerMessage(
            customerName = "Dana TS",
            balanceCents = 79_900,
            entries = listOf(
                StatementEntry("2026-08-03", "CHARGE", "DEBIT", 79_900, null, saleId = "s1", detail = "2 Jabón Zote"),
            ),
        )
        assertTrue(text.contains("Dana TS"))
        assertFalse("el nombre de otra clienta no puede viajar", text.contains("Beto"))
        assertFalse("ni su saldo", text.contains(Money.format(40_000)))
    }
}
