package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Que la imagen diga lo correcto **antes** de que exista un píxel.
 *
 * Todo esto es Kotlin puro: nada de `Context` ni de `R.string`. El dibujo se prueba aparte, en el
 * emulador, porque ahí sí hace falta Android.
 */
class StatementImageTest {

    private fun entry(index: Int, type: String, direction: String, cents: Long, detail: String? = null) =
        StatementEntry("2026-08-%02d".format(index % 28 + 1), type, direction, cents, null, detail = detail)

    /** N movimientos con importes distintos, para poder distinguir uno de otro. */
    private fun movements(count: Int) = (1..count).map { entry(it, "CHARGE", "DEBIT", 10_000L * it) }

    private fun build(
        entries: List<StatementEntry> = emptyList(),
        balanceCents: Long = 99_900,
        businessName: String? = "Abarrotes Don Luis",
        customerName: String = "Sra. Mendoza",
        tier: SubscriptionTier = SubscriptionTier.FREE,
        statementDateIso: String = "2026-09-20",
    ) = StatementImage.build(businessName, customerName, balanceCents, entries, tier, statementDateIso)

    @Test
    fun `cada_linea_dice_lo_mismo_que_el_texto_que_se_manda_hoy`() {
        // La forma de la línea es una sola (`StatementLineFormat`), así que la imagen no puede decir
        // "Venta" donde el texto dice "Compra", ni ponerle "+" a una compra.
        val content = build(
            entries = listOf(
                StatementEntry("2026-08-03", "CHARGE", "DEBIT", 79_900, null, detail = "2 Jabón Zote"),
                StatementEntry("2026-08-12", "PAYMENT", "CREDIT", 20_000, null),
            ),
        )

        assertEquals("03/08/2026 · Compra · $799.00", content.movements[0].line)
        assertEquals("2 Jabón Zote", content.movements[0].detail)
        assertEquals("12/08/2026 · Abono · −$200.00", content.movements[1].line)
        assertNull("un abono no trae detalle si la hoja no lo dice", content.movements[1].detail)
    }

    @Test
    fun `con_cero_movimientos_no_hay_lineas_ni_cuenta_de_lo_que_falta`() {
        val content = build(entries = emptyList())

        assertTrue(content.movements.isEmpty())
        assertNull(content.moreLine)
    }

    @Test
    fun `con_uno_o_diez_entran_todos_y_no_se_dice_que_falte_nada`() {
        for (count in listOf(1, 10)) {
            val content = build(entries = movements(count))
            assertEquals("con $count tienen que entrar todos", count, content.movements.size)
            assertNull("con $count no sobra nada", content.moreLine)
        }
    }

    @Test
    fun `con_once_dice_y_1_movimiento_anterior_en_singular`() {
        val content = build(entries = movements(11))

        assertEquals(StatementImage.MAX_MOVEMENTS, content.movements.size)
        assertEquals("y 1 movimiento anterior", content.moreLine)
    }

    @Test
    fun `con_cuarenta_dice_y_30_movimientos_anteriores_y_ensena_los_ultimos_diez`() {
        val all = movements(40)
        val content = build(entries = all)

        assertEquals(10, content.movements.size)
        assertEquals("y 30 movimientos anteriores", content.moreLine)
        // "Los últimos": los diez dibujados son los diez del final de la lista, no los primeros.
        assertEquals(all.takeLast(10).map { StatementLineFormat.movementLine(it) }, content.movements.map { it.line })
    }

    @Test
    fun `el_saldo_es_el_real_aunque_se_recorten_los_movimientos`() {
        // El saldo que se enseña no es la suma de los diez visibles: recortar la lista no puede
        // recortar la deuda. Es lo que la persona mira primero.
        val content = build(entries = movements(40), balanceCents = 1_234_500)

        assertEquals(Money.format(1_234_500), content.balanceText)
        assertEquals(StatementImage.BALANCE_LABEL, content.balanceLabel)
    }

    @Test
    fun `sin_nombre_de_negocio_el_titulo_es_solo_estado_de_cuenta`() {
        for (name in listOf(null, "", "   ", BusinessName.DEFAULT, "  mi NEGOCIO ")) {
            assertEquals("con '$name' no puede salir el nombre por defecto", StatementImage.TITLE_WITHOUT_BUSINESS_NAME, build(businessName = name).title)
        }
    }

    @Test
    fun `con_nombre_configurado_el_titulo_lo_lleva_y_sin_espacios_de_sobra`() {
        assertEquals("Papelería La Estrella", build(businessName = "  Papelería La Estrella  ").title)
    }

    @Test
    fun `el_sello_va_en_gratis_y_no_en_los_planes_de_pago`() {
        assertTrue(build(tier = SubscriptionTier.FREE).showsStamp)
        // Con includesProBenefits: un plan de pago nuevo tampoco lleva marca.
        assertFalse(build(tier = SubscriptionTier.PRO).showsStamp)
        assertFalse(build(tier = SubscriptionTier.AI).showsStamp)
    }

    @Test
    fun `una_cuenta_al_corriente_no_pide_dinero`() {
        val content = build(entries = movements(3), balanceCents = 0)

        assertNull("no hay saldo que enseñar", content.balanceText)
        assertEquals(StatementImage.SETTLED_LABEL, content.balanceLabel)
    }

    @Test
    fun `la_fecha_del_estado_de_cuenta_llega_del_llamador_y_se_formatea`() {
        val content = build(statementDateIso = "2026-09-20")

        assertEquals("Estado de cuenta al 20/09/2026", content.statementDateLine)
    }

    @Test
    fun `la_imagen_de_un_cliente_nunca_lleva_el_nombre_ni_el_saldo_de_otro`() {
        // Una imagen se reenvía sin pensar, así que esta invariante pesa aquí más que en el texto.
        val content = build(customerName = "Sra. Mendoza", entries = movements(3))
        val everything = listOf(content.title, content.customerName, content.balanceText.orEmpty()) +
            content.movements.flatMap { listOfNotNull(it.line, it.detail) } +
            listOfNotNull(content.moreLine)

        assertTrue(everything.any { it.contains("Sra. Mendoza") })
        assertFalse("ni el nombre de otra clienta", everything.any { it.contains("Beto") })
        assertFalse("ni su saldo", everything.any { it.contains(Money.format(40_000)) })
    }
}
