package com.cuentivo.app.domain

/**
 * Qué texto acompaña al estado de cuenta cuando se comparte (v44).
 *
 * La ruta "imagen + texto" manda los dos: WhatsApp los junta y el texto queda como pie de foto. Si
 * la imagen se pudo escribir, el texto va **corto** —sin movimientos, que ya van en la imagen—. Si
 * la imagen **falla** (sin espacio, error de disco: `createShareIntent` lanza), la persona pierde la
 * imagen pero no el estado de cuenta: se comparte el texto **completo** de siempre, con movimientos.
 *
 * La decisión vive aquí, pura y probada, y no dentro del `try` de la Activity: así el respaldo se
 * puede verificar sin un teléfono y sin un disco lleno.
 */
object StatementShareTextChoice {

    /**
     * El texto a compartir.
     *
     * @param imageSucceeded si el PNG se pudo escribir y se va a compartir.
     * @param shortText el texto corto, para cuando la imagen va.
     * @param fullText el texto completo con movimientos, para cuando la imagen no va.
     */
    fun choose(imageSucceeded: Boolean, shortText: String, fullText: String): String =
        if (imageSucceeded) shortText else fullText
}
