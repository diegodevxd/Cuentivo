package com.cuentivo.app.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.cuentivo.app.domain.StatementImageContent
import com.cuentivo.app.ui.theme.CuentivoPalette

/**
 * Los colores de la imagen, tomados del tema de la app.
 *
 * En `Int` ARGB porque es lo que un `Paint` come; los tokens del tema viven como `Long` (los usa
 * Compose), así que la conversión se hace aquí una vez y no en cada trazo.
 */
internal data class StatementImagePalette(
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
 * Dibuja la imagen del estado de cuenta sobre un `Bitmap`, con `Canvas` y `Paint`.
 *
 * **No es un Composable renderizado fuera de pantalla**, y es a propósito: esto no necesita ventana ni
 * árbol de vistas, así que sale igual siempre y se puede llamar desde una función normal —incluida una
 * prueba instrumentada que no monta la app.
 *
 * **Ancho fijo de 1080** y alto según el contenido. El fondo se pinta **sólido**: WhatsApp dibuja de
 * negro lo transparente, así que un PNG con alfa llegaría con manchas negras.
 *
 * Nada de esto decide *qué* se dice: eso lo resolvió `StatementImage` en `domain/`, que es lo que se
 * prueba en JVM. Aquí sólo se mide y se pinta.
 */
internal object StatementImageRenderer {

    const val WIDTH_PX = 1080

    /** El sello de quien no paga, con las mismas palabras que el pie del texto. */
    const val STAMP_TEXT = "Enviado con Cuentivo · Gratis en Google Play"

    private const val PADDING = 64
    private const val DIVIDER_THICKNESS = 2
    private const val GAP_AFTER_TITLE = 14
    private const val GAP_BEFORE_DIVIDER = 44
    private const val GAP_AFTER_DIVIDER = 44
    private const val GAP_BETWEEN_MOVEMENTS = 30
    private const val GAP_DETAIL = 8
    private const val GAP_BEFORE_MORE = 28
    private const val GAP_BEFORE_BALANCE = 60
    private const val GAP_BEFORE_FOOTER = 52
    private const val GAP_BETWEEN_FOOTER_LINES = 12

    private const val SIZE_TITLE = 56f
    private const val SIZE_CUSTOMER = 44f
    private const val SIZE_MOVEMENT = 36f
    private const val SIZE_DETAIL = 30f
    private const val SIZE_SPECIAL = 32f
    private const val SIZE_BALANCE_VALUE = 88f
    private const val SIZE_STAMP = 26f

    fun render(
        content: StatementImageContent,
        palette: StatementImagePalette = StatementImagePalette.fromAppTheme(),
    ): Bitmap {
        val pieces = buildPieces(content, palette)
        val height = pieces.sumOf { it.height }
        val bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette.background)
        var top = 0
        for (piece in pieces) {
            piece.draw(canvas, top)
            top += piece.height
        }
        return bitmap
    }

    private fun buildPieces(content: StatementImageContent, palette: StatementImagePalette): List<Piece> = buildList {
        val width = WIDTH_PX - PADDING * 2
        add(Gap(PADDING))
        add(text(content.title, SIZE_TITLE, palette.title, width, bold = true))
        add(Gap(GAP_AFTER_TITLE))
        add(text(content.customerName, SIZE_CUSTOMER, palette.customer, width, bold = true))
        add(Gap(GAP_BEFORE_DIVIDER))
        add(Divider(palette.divider))
        add(Gap(GAP_AFTER_DIVIDER))
        content.movements.forEachIndexed { index, movement ->
            if (index > 0) add(Gap(GAP_BETWEEN_MOVEMENTS))
            add(text(movement.line, SIZE_MOVEMENT, palette.movement, width))
            movement.detail?.let {
                add(Gap(GAP_DETAIL))
                add(text(it, SIZE_DETAIL, palette.detail, width))
            }
        }
        content.moreLine?.let {
            add(Gap(GAP_BEFORE_MORE))
            add(text(it, SIZE_SPECIAL, palette.more, width))
        }
        add(Gap(GAP_BEFORE_BALANCE))
        add(text(content.balanceLabel, SIZE_SPECIAL, palette.balanceLabel, width))
        content.balanceText?.let {
            add(text(it, SIZE_BALANCE_VALUE, palette.balanceValue, width, bold = true))
        }
        add(Gap(GAP_BEFORE_FOOTER))
        add(text(content.statementDateLine, SIZE_STAMP, palette.stamp, width))
        if (content.showsStamp) {
            add(Gap(GAP_BETWEEN_FOOTER_LINES))
            add(text(STAMP_TEXT, SIZE_STAMP, palette.stamp, width))
        }
        add(Gap(PADDING))
    }

    private fun text(value: String, size: Float, color: Int, width: Int, bold: Boolean = false): Piece =
        Text(value, size, color, width, bold)

    /** Una pieza con altura conocida, para medir primero y pintar después con la misma cuenta. */
    private sealed interface Piece {
        val height: Int
        fun draw(canvas: Canvas, top: Int)
    }

    private class Gap(override val height: Int) : Piece {
        override fun draw(canvas: Canvas, top: Int) = Unit
    }

    private class Divider(color: Int) : Piece {
        private val paint = Paint().apply {
            this.color = color
            isAntiAlias = true
        }
        override val height = DIVIDER_THICKNESS
        override fun draw(canvas: Canvas, top: Int) {
            canvas.drawRect(
                PADDING.toFloat(),
                top.toFloat(),
                (WIDTH_PX - PADDING).toFloat(),
                (top + DIVIDER_THICKNESS).toFloat(),
                paint,
            )
        }
    }

    /**
     * Un texto que **se parte solo** cuando no cabe.
     *
     * `StaticLayout` es del framework —cero dependencias— y es lo que hace que un nombre de negocio
     * larguísimo se acomode en varias líneas en vez de salirse del ancho. La misma instancia se usa para
     * medir y para pintar, así que la altura no puede discrepar de lo dibujado.
     */
    private class Text(value: String, size: Float, color: Int, width: Int, bold: Boolean) : Piece {
        private val paint = TextPaint().apply {
            this.color = color
            textSize = size
            isAntiAlias = true
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        private val layout: StaticLayout = StaticLayout.Builder
            .obtain(value, 0, value.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

        override val height: Int = layout.height + paint.fontMetrics.bottom.toInt().coerceAtLeast(0) / 4

        override fun draw(canvas: Canvas, top: Int) {
            canvas.save()
            canvas.translate(PADDING.toFloat(), top.toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
