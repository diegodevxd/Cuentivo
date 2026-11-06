package com.cuentivo.app.platform

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El reparto entre imagen y texto, con las dos ramas ejercidas de verdad.
 *
 * La prueba no copia la regla: llama a [StatementShareFlow.share], el mismo objeto que usan Android e
 * iOS, y comprueba qué texto salió por cada camino.
 */
class StatementShareFlowTest {

    private val shortText = "corto"
    private val fullText = "completo"

    @Test
    fun `con imagen se comparte el texto corto`() {
        var imageText: String? = null
        var textShared: String? = null

        StatementShareFlow.share(
            subject = "Estado de cuenta",
            shortText = shortText,
            fullText = fullText,
            shareImage = { text -> imageText = text; true },
            shareText = { text -> textShared = text },
        )

        assertEquals(shortText, imageText)
        assertEquals(null, textShared)
    }

    @Test
    fun `si la imagen no sale se comparte el texto completo`() {
        var imageTried = false
        var textShared: String? = null

        StatementShareFlow.share(
            subject = "Estado de cuenta",
            shortText = shortText,
            fullText = fullText,
            shareImage = { imageTried = true; false },
            shareText = { text -> textShared = text },
        )

        assertTrue(imageTried)
        assertEquals(fullText, textShared)
    }

    @Test
    fun `si la imagen lanza se comparte el texto completo`() {
        var textShared: String? = null

        StatementShareFlow.share(
            subject = "Estado de cuenta",
            shortText = shortText,
            fullText = fullText,
            shareImage = { throw IllegalStateException("sin espacio en disco") },
            shareText = { text -> textShared = text },
        )

        assertEquals(fullText, textShared)
    }

    @Test
    fun `la cancelacion no se traga ni comparte nada`() {
        var textShared: String? = null
        var thrown = false

        try {
            StatementShareFlow.share(
                subject = "Estado de cuenta",
                shortText = shortText,
                fullText = fullText,
                shareImage = { throw CancellationException("cancelado") },
                shareText = { text -> textShared = text },
            )
        } catch (_: CancellationException) {
            thrown = true
        }

        assertTrue(thrown, "CancellationException tiene que relanzarse")
        assertEquals(null, textShared)
    }
}
