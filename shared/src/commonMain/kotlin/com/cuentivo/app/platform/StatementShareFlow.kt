package com.cuentivo.app.platform

import com.cuentivo.app.domain.StatementShareTextChoice
import kotlin.coroutines.cancellation.CancellationException

/**
 * El reparto entre "con imagen" y "sólo texto", puro y probado.
 *
 * Las **dos** ramas pasan por [StatementShareTextChoice.choose], igual que hoy corre en Android: si
 * la imagen se pudo escribir, el texto va corto; si no (sin espacio, error de disco), se comparte el
 * texto completo de siempre. La persona pierde la imagen, no el estado de cuenta.
 */
object StatementShareFlow {

    /**
     * @param subject asunto de la hoja de compartir. Viaja por la firma porque es parte del mismo
     *   gesto, aunque quien escribe y quien manda el texto sean los dos closures.
     * @param shareImage escribe la imagen y abre la hoja; `false` (o una excepción) significa que no
     *   salió, y entonces se manda el texto completo.
     */
    @Suppress("UNUSED_PARAMETER")
    fun share(
        subject: String,
        shortText: String,
        fullText: String,
        shareImage: (String) -> Boolean,
        shareText: (String) -> Unit,
    ) {
        val textWithImage = StatementShareTextChoice.choose(true, shortText, fullText)
        val imageWentOut = try {
            shareImage(textWithImage)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
        if (!imageWentOut) {
            shareText(StatementShareTextChoice.choose(false, shortText, fullText))
        }
    }
}

/**
 * El `tel:` que se le da al sistema, a partir de lo que escribió la persona.
 *
 * Conserva los dígitos y un `+` inicial; cualquier otra cosa (espacios, guiones, paréntesis) se cae.
 * Sin ningún dígito no hay teléfono: `null`, y quien llama no abre nada.
 */
object TelUri {
    fun from(phone: String): String? {
        val trimmed = phone.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val prefix = if (trimmed.startsWith("+")) "+" else ""
        return prefix + digits
    }
}
