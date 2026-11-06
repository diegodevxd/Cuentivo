package com.cuentivo.app.ui.share

import com.cuentivo.app.domain.StatementImageContent
import com.cuentivo.app.platform.StoreListing
import com.cuentivo.app.ui.theme.CuentivoPalette

/**
 * Los colores de la imagen del estado de cuenta, tomados del tema de la app.
 *
 * En `Int` ARGB porque es lo que comen los pinceles de las dos plataformas; los tokens del tema
 * viven como `Long`, así que la conversión se hace una vez aquí y no en cada trazo.
 */
data class StatementImagePalette(
    val background: Int,
    val title: Int,
    val customer: Int,
    val movement: Int,
    val detail: Int,
    val more: Int,
    val balanceLabel: Int,
    val balanceValue: Int,
    val divider: Int,
    val stamp: Int,
) {
    companion object {
        /** El tema oscuro de la app, con los mismos valores que usan las pantallas. */
        fun fromAppTheme(): StatementImagePalette = with(CuentivoPalette.Dark) {
            StatementImagePalette(
                background = background.toInt(),
                title = onBackground.toInt(),
                customer = primary.toInt(),
                movement = onBackground.toInt(),
                detail = onSurfaceVariant.toInt(),
                more = onSurfaceVariant.toInt(),
                balanceLabel = onSurfaceVariant.toInt(),
                // Blanco, no el verde de los abonos: el saldo es lo que se debe, no algo pagado.
                balanceValue = onBackground.toInt(),
                divider = outlineVariant.toInt(),
                stamp = onSurfaceVariant.toInt(),
            )
        }
    }
}

/**
 * Los números de la imagen, en un solo lugar.
 *
 * Son los mismos que tenía el renderizador de Android: extraerlos no cambia ni un píxel, pero ahora
 * los dos renderizadores miden con la misma escala.
 */
object StatementImageLayout {
    /** Ancho fijo de la imagen. */
    const val WIDTH_PX = 1080

    const val PADDING = 64
    const val DIVIDER_THICKNESS = 2
    const val GAP_AFTER_TITLE = 14
    const val GAP_BEFORE_DIVIDER = 44
    const val GAP_AFTER_DIVIDER = 44
    const val GAP_BETWEEN_MOVEMENTS = 30
    const val GAP_DETAIL = 8
    const val GAP_BEFORE_MORE = 28
    const val GAP_BEFORE_BALANCE = 60
    const val GAP_BEFORE_FOOTER = 52
    const val GAP_BETWEEN_FOOTER_LINES = 12

    const val SIZE_TITLE = 56f
    const val SIZE_CUSTOMER = 44f
    const val SIZE_MOVEMENT = 36f
    const val SIZE_DETAIL = 30f
    const val SIZE_SPECIAL = 32f
    const val SIZE_BALANCE_VALUE = 88f
    const val SIZE_STAMP = 26f

    /** El ancho útil para el texto: la imagen menos los dos márgenes. */
    const val TEXT_WIDTH = WIDTH_PX - PADDING * 2
}

/**
 * Una pieza de la imagen, **sin medir**: qué se pinta y con qué, no cuánto ocupa.
 *
 * La altura del texto depende de cómo mida cada plataforma (StaticLayout en Android,
 * boundingRectWithSize en iOS), así que medir es trabajo del renderizador. Lo que sí es común es el
 * orden y los números.
 */
sealed interface StatementImagePiece {
    data class Gap(val height: Int) : StatementImagePiece
    data class Divider(val color: Int) : StatementImagePiece

    /** Un texto que se parte solo cuando no cabe en [StatementImageLayout.TEXT_WIDTH]. */
    data class Text(val value: String, val size: Float, val color: Int, val bold: Boolean) : StatementImagePiece
}

/**
 * El orden de las piezas. Decide **qué** se dice y en qué orden; no decide cómo se mide.
 *
 * El sello sale de [StoreListing.footerLine]: la misma firma que el pie del texto, para que la
 * imagen y el mensaje no digan cosas distintas. En iOS dice sólo "Enviado con Cuentivo", porque ahí
 * no hay Google Play.
 */
object StatementImagePieces {

    fun pieces(content: StatementImageContent, palette: StatementImagePalette): List<StatementImagePiece> =
        buildList {
            add(StatementImagePiece.Gap(StatementImageLayout.PADDING))
            add(text(content.title, StatementImageLayout.SIZE_TITLE, palette.title, bold = true))
            add(StatementImagePiece.Gap(StatementImageLayout.GAP_AFTER_TITLE))
            add(text(content.customerName, StatementImageLayout.SIZE_CUSTOMER, palette.customer, bold = true))
            add(StatementImagePiece.Gap(StatementImageLayout.GAP_BEFORE_DIVIDER))
            add(StatementImagePiece.Divider(palette.divider))
            add(StatementImagePiece.Gap(StatementImageLayout.GAP_AFTER_DIVIDER))
            content.movements.forEachIndexed { index, movement ->
                if (index > 0) add(StatementImagePiece.Gap(StatementImageLayout.GAP_BETWEEN_MOVEMENTS))
                add(text(movement.line, StatementImageLayout.SIZE_MOVEMENT, palette.movement))
                movement.detail?.let {
                    add(StatementImagePiece.Gap(StatementImageLayout.GAP_DETAIL))
                    add(text(it, StatementImageLayout.SIZE_DETAIL, palette.detail))
                }
            }
            content.moreLine?.let {
                add(StatementImagePiece.Gap(StatementImageLayout.GAP_BEFORE_MORE))
                add(text(it, StatementImageLayout.SIZE_SPECIAL, palette.more))
            }
            add(StatementImagePiece.Gap(StatementImageLayout.GAP_BEFORE_BALANCE))
            add(text(content.balanceLabel, StatementImageLayout.SIZE_SPECIAL, palette.balanceLabel))
            content.balanceText?.let {
                add(text(it, StatementImageLayout.SIZE_BALANCE_VALUE, palette.balanceValue, bold = true))
            }
            add(StatementImagePiece.Gap(StatementImageLayout.GAP_BEFORE_FOOTER))
            add(text(content.statementDateLine, StatementImageLayout.SIZE_STAMP, palette.stamp))
            if (content.showsStamp) {
                add(StatementImagePiece.Gap(StatementImageLayout.GAP_BETWEEN_FOOTER_LINES))
                add(text(StoreListing.footerLine, StatementImageLayout.SIZE_STAMP, palette.stamp))
            }
            add(StatementImagePiece.Gap(StatementImageLayout.PADDING))
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): StatementImagePiece =
        StatementImagePiece.Text(value = value, size = size, color = color, bold = bold)
}
