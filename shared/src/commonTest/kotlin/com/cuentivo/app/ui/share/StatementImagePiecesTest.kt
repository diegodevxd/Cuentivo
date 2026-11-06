package com.cuentivo.app.ui.share

import com.cuentivo.app.domain.StatementImage
import com.cuentivo.app.domain.StatementImageContent
import com.cuentivo.app.platform.StoreListing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El orden y las piezas de la imagen, sin medir nada.
 *
 * Lo que se prueba es lo que las dos plataformas comparten: qué se pinta y en qué orden. Cuánto mide
 * cada texto lo resuelve cada renderizador.
 */
class StatementImagePiecesTest {

    private val palette = StatementImagePalette.fromAppTheme()

    private fun content(
        moreLine: String? = null,
        balanceText: String? = "1,000.00",
        showsStamp: Boolean = true,
    ) = StatementImageContent(
        title = "Abarrotes Don Luis",
        customerName = "Rosa",
        movements = listOf(StatementImageContent.Movement("Compra 500.00", detail = null)),
        moreLine = moreLine,
        balanceLabel = "Saldo pendiente",
        balanceText = balanceText,
        showsStamp = showsStamp,
        statementDateLine = "Estado de cuenta al 6 de noviembre",
    )

    @Test
    fun `la imagen empieza y termina con el margen`() {
        val pieces = StatementImagePieces.pieces(content(), palette)

        assertEquals(StatementImagePiece.Gap(StatementImageLayout.PADDING), pieces.first())
        assertEquals(StatementImagePiece.Gap(StatementImageLayout.PADDING), pieces.last())
    }

    @Test
    fun `el titulo va antes del divisor y los movimientos despues`() {
        val pieces = StatementImagePieces.pieces(content(), palette)
        val divider = pieces.indexOfFirst { it is StatementImagePiece.Divider }
        val title = pieces.indexOf(StatementImagePiece.Text("Abarrotes Don Luis", StatementImageLayout.SIZE_TITLE, palette.title, bold = true))
        val customer = pieces.indexOf(StatementImagePiece.Text("Rosa", StatementImageLayout.SIZE_CUSTOMER, palette.customer, bold = true))
        val movement = pieces.indexOf(StatementImagePiece.Text("Compra 500.00", StatementImageLayout.SIZE_MOVEMENT, palette.movement, bold = false))

        assertTrue(title >= 0, "el título tiene que estar")
        assertTrue(customer > title, "el cliente va después del título")
        assertTrue(divider > customer, "el divisor va después del cliente")
        assertTrue(movement > divider, "los movimientos van después del divisor")
    }

    @Test
    fun `el sello aparece solo cuando la imagen lo pide`() {
        val conSello = StatementImagePieces.pieces(content(showsStamp = true), palette)
        val sinSello = StatementImagePieces.pieces(content(showsStamp = false), palette)

        assertTrue(conSello.any { it is StatementImagePiece.Text && it.value == StoreListing.footerLine })
        assertTrue(sinSello.none { it is StatementImagePiece.Text && it.value == StoreListing.footerLine })
    }

    @Test
    fun `moreLine y balanceText son opcionales`() {
        val sinOpcionales = StatementImagePieces.pieces(content(moreLine = null, balanceText = null), palette)
        val conOpcionales = StatementImagePieces.pieces(content(moreLine = "y 3 movimientos anteriores", balanceText = "1,000.00"), palette)

        assertTrue(sinOpcionales.none { it is StatementImagePiece.Text && it.value == "y 3 movimientos anteriores" })
        assertTrue(sinOpcionales.none { it is StatementImagePiece.Text && it.value == "1,000.00" })
        assertTrue(conOpcionales.any { it is StatementImagePiece.Text && it.value == "y 3 movimientos anteriores" })
        assertTrue(conOpcionales.any { it is StatementImagePiece.Text && it.value == "1,000.00" })
    }

    @Test
    fun `el titulo sin nombre de negocio usa el de la pantalla`() {
        val content = StatementImage.build(
            businessName = null,
            customerName = "Rosa",
            balanceCents = 0,
            entries = emptyList(),
            tier = com.cuentivo.app.domain.SubscriptionTier.FREE,
            statementDateIso = "2026-11-06",
        )

        assertTrue(StatementImagePieces.pieces(content, palette).any {
            it is StatementImagePiece.Text && it.value == "Estado de cuenta"
        })
    }
}
