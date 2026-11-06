package com.cuentivo.app.platform

import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.domain.StatementImage
import com.cuentivo.app.domain.SubscriptionTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * La imagen de iOS, comprobada sobre el PNG de verdad.
 *
 * El ancho y el alto se leen del IHDR del PNG, y el tipo de color también (2 = RGB sin canal alfa,
 * 6 = RGBA): es la forma de afirmar "sin alfa" sin depender de cómo UIKit reporte el `CGImage`.
 *
 * Pendiente de CI: esta prueba sólo corre en `:shared:iosSimulatorArm64Test`.
 */
class IosStatementImageRendererTest {

    @Test
    fun `el png sale de 1080 de ancho con alto positivo y sin alfa`() {
        val content = StatementImage.build(
            businessName = "Abarrotes Don Luis",
            customerName = "Rosa",
            balanceCents = 59_900,
            entries = listOf(
                StatementEntry("2026-08-01", "CHARGE", "DEBIT", 79_900, null),
                StatementEntry("2026-08-12", "PAYMENT", "CREDIT", 20_000, null),
            ),
            tier = SubscriptionTier.FREE,
            statementDateIso = "2026-11-06",
        )

        val png = IosStatementImageRenderer.renderPng(content)
        assertNotNull(png)

        assertEquals(1080, readBigEndianInt(png, 16), "ancho del IHDR")
        assertTrue(readBigEndianInt(png, 20) > 0, "el alto tiene que ser positivo")
        assertEquals(2, png[25].toInt(), "tipo de color: 2 es RGB sin canal alfa")
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
