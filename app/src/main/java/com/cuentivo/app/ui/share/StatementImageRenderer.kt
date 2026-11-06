package com.cuentivo.app.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.cuentivo.app.domain.StatementImageContent

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
 * Qué se dice y en qué orden lo decidió [StatementImagePieces] en `commonMain` (antes vivía aquí
 * dentro); esto sólo **mide** cada pieza y la pinta, con los mismos números de
 * [StatementImageLayout]. Android mide con `StaticLayout`, que es lo que hace que un nombre largo se
 * acomode en varias líneas en vez de salirse del ancho.
 */
internal object StatementImageRenderer {

    const val WIDTH_PX = StatementImageLayout.WIDTH_PX

    fun render(
        content: StatementImageContent,
        palette: StatementImagePalette = StatementImagePalette.fromAppTheme(),
    ): Bitmap {
        val measured = StatementImagePieces.pieces(content, palette).map { measure(it) }
        val height = measured.sumOf { it.height }
        val bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette.background)
        var top = 0
        for (piece in measured) {
            piece.draw(canvas, top)
            top += piece.height
        }
        return bitmap
    }

    /** Una pieza ya medida: la altura sale de su texto o de su número fijo. */
    private sealed interface MeasuredPiece {
        val height: Int
        fun draw(canvas: Canvas, top: Int)
    }

    private fun measure(piece: StatementImagePiece): MeasuredPiece = when (piece) {
        is StatementImagePiece.Gap -> GapPiece(piece.height)
        is StatementImagePiece.Divider -> DividerPiece(piece.color)
        is StatementImagePiece.Text -> TextPiece(piece)
    }

    private class GapPiece(override val height: Int) : MeasuredPiece {
        override fun draw(canvas: Canvas, top: Int) = Unit
    }

    private class DividerPiece(color: Int) : MeasuredPiece {
        private val paint = Paint().apply {
            this.color = color
            isAntiAlias = true
        }
        override val height = StatementImageLayout.DIVIDER_THICKNESS
        override fun draw(canvas: Canvas, top: Int) {
            canvas.drawRect(
                StatementImageLayout.PADDING.toFloat(),
                top.toFloat(),
                (WIDTH_PX - StatementImageLayout.PADDING).toFloat(),
                (top + StatementImageLayout.DIVIDER_THICKNESS).toFloat(),
                paint,
            )
        }
    }

    /**
     * Un texto que **se parte solo** cuando no cabe.
     *
     * La misma instancia del layout se usa para medir y para pintar, así que la altura no puede
     * discrepar de lo dibujado.
     */
    private class TextPiece(piece: StatementImagePiece.Text) : MeasuredPiece {
        private val paint = TextPaint().apply {
            color = piece.color
            textSize = piece.size
            isAntiAlias = true
            typeface = if (piece.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        private val layout: StaticLayout = StaticLayout.Builder
            .obtain(piece.value, 0, piece.value.length, paint, StatementImageLayout.TEXT_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

        override val height: Int = layout.height + paint.fontMetrics.bottom.toInt().coerceAtLeast(0) / 4

        override fun draw(canvas: Canvas, top: Int) {
            canvas.save()
            canvas.translate(StatementImageLayout.PADDING.toFloat(), top.toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
