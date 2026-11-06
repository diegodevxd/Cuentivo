package com.cuentivo.app.platform

import com.cuentivo.app.domain.StatementImageContent
import com.cuentivo.app.ui.share.StatementImageLayout
import com.cuentivo.app.ui.share.StatementImagePalette
import com.cuentivo.app.ui.share.StatementImagePiece
import com.cuentivo.app.ui.share.StatementImagePieces
import kotlin.math.ceil
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSFontAttributeName
import platform.Foundation.NSForegroundColorAttributeName
import platform.Foundation.NSString
import platform.Foundation.NSStringDrawingUsesLineFragmentOrigin
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIRectFill

/**
 * Pinta la imagen del estado de cuenta en iOS, con las **mismas piezas** que Android.
 *
 * `scale = 1` y `opaque = true` no son detalles: con `scale` la imagen saldría al doble de píxeles en
 * un teléfono Retina, y con transparencia WhatsApp la pinta de negro. El fondo se pinta sólido.
 *
 * Se mide con `boundingRectWithSize` y se dibuja el mismo rectángulo, así que la altura no puede
 * discrepar de lo que se ve. Devuelve los bytes del PNG, o `null` si UIKit no pudo generarlos.
 */
@OptIn(ExperimentalForeignApi::class)
object IosStatementImageRenderer {

    const val WIDTH_PX = StatementImageLayout.WIDTH_PX

    fun renderPng(
        content: StatementImageContent,
        palette: StatementImagePalette = StatementImagePalette.fromAppTheme(),
    ): ByteArray? {
        val measured = StatementImagePieces.pieces(content, palette).map { measure(it) }
        val height = measured.sumOf { it.height }
        if (height <= 0.0) return null

        val format = UIGraphicsImageRendererFormat.defaultFormat()
        format.opaque = true
        format.scale = 1.0
        val renderer = UIGraphicsImageRenderer(
            size = CGSizeMake(WIDTH_PX.toDouble(), height),
            format = format,
        )

        val image = renderer.imageWithActions {
            palette.background.toUIColor().setFill()
            UIRectFill(CGRectMake(0.0, 0.0, WIDTH_PX.toDouble(), height))
            var top = 0.0
            for (piece in measured) {
                piece.draw(top)
                top += piece.height
            }
        }

        val data = UIImagePNGRepresentation(image) ?: return null
        return data.bytes?.readBytes(data.length.toInt())
    }

    private sealed interface Measured {
        val height: Double
        fun draw(top: Double)
    }

    private fun measure(piece: StatementImagePiece): Measured = when (piece) {
        is StatementImagePiece.Gap -> GapPiece(piece.height.toDouble())
        is StatementImagePiece.Divider -> DividerPiece(piece.color.toUIColor())
        is StatementImagePiece.Text -> TextPiece(piece)
    }

    private class GapPiece(override val height: Double) : Measured {
        override fun draw(top: Double) = Unit
    }

    private class DividerPiece(private val color: UIColor) : Measured {
        override val height = StatementImageLayout.DIVIDER_THICKNESS.toDouble()

        override fun draw(top: Double) {
            color.setFill()
            UIRectFill(
                CGRectMake(
                    StatementImageLayout.PADDING.toDouble(),
                    top,
                    StatementImageLayout.TEXT_WIDTH.toDouble(),
                    height,
                ),
            )
        }
    }

    /**
     * Un texto que **se parte solo** cuando no cabe.
     *
     * La misma medida se usa para el alto y para el rectángulo que se pinta, así que no puede
     * discrepar.
     */
    private class TextPiece(piece: StatementImagePiece.Text) : Measured {
        private val value = piece.value
        private val font = if (piece.bold) UIFont.boldSystemFontOfSize(piece.size.toDouble())
        else UIFont.systemFontOfSize(piece.size.toDouble())
        private val color = piece.color.toUIColor()
        private val attributes: Map<Any?, Any?> = mapOf(
            NSFontAttributeName to font,
            NSForegroundColorAttributeName to color,
        )

        private val bounds = (value as NSString).boundingRectWithSize(
            size = CGSizeMake(StatementImageLayout.TEXT_WIDTH.toDouble(), Double.MAX_VALUE),
            options = NSStringDrawingUsesLineFragmentOrigin,
            attributes = attributes,
            context = null,
        )

        override val height: Double = ceil(bounds.useContents { size.height })

        override fun draw(top: Double) {
            (value as NSString).drawWithRect(
                rect = CGRectMake(
                    StatementImageLayout.PADDING.toDouble(),
                    top,
                    StatementImageLayout.TEXT_WIDTH.toDouble(),
                    height,
                ),
                options = NSStringDrawingUsesLineFragmentOrigin,
                attributes = attributes,
                context = null,
            )
        }
    }
}

/** ARGB (`Int`, como en el tema) → `UIColor`. */
@OptIn(ExperimentalForeignApi::class)
private fun Int.toUIColor(): UIColor {
    val alpha = ((this shr 24) and 0xFF) / 255.0
    val red = ((this shr 16) and 0xFF) / 255.0
    val green = ((this shr 8) and 0xFF) / 255.0
    val blue = (this and 0xFF) / 255.0
    return UIColor.colorWithRed(red = red, green = green, blue = blue, alpha = alpha)
}
