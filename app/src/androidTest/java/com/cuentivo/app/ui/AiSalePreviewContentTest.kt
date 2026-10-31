package com.cuentivo.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.R
import com.cuentivo.app.ai.AiEntityResolutionMode
import com.cuentivo.app.ai.AiResolvedSaleLine
import com.cuentivo.app.ai.AiResolvedSalePreview
import com.cuentivo.app.ui.ai.AiSalePreviewContent
import com.cuentivo.app.ui.ai.formatCents
import org.junit.Rule
import org.junit.Test

/**
 * Prueba conductual de la vista previa de venta de CuentivoAI.
 *
 * Reemplaza a la vieja guarda que leía `AiDebugLabScreen.kt` como texto y buscaba el identificador
 * `ai_sale_preview_new_product_habitual_price`: eso probaba que una cadena estaba escrita, no que el
 * render la omitiera. Aquí se renderiza el **mismo composable de producción** que usa el laboratorio
 * (`AiSalePreviewContent`) con un producto `NEW`, y se afirma sobre lo que se ve.
 *
 * Debe quedar roja si la fila "Precio habitual" reapareciera en el camino de producción.
 */
class AiSalePreviewContentTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun new_product_preview_renders_the_product_and_price_without_a_habitual_price_row() {
        val preview = AiResolvedSalePreview(
            confirmationKey = "k",
            customerId = "c1",
            customerName = "Rosa",
            rawLines = listOf(
                AiResolvedSaleLine(
                    productId = "p1",
                    productName = "Producto nuevo",
                    quantity = 2,
                    unitPriceCents = 12_500,
                    lineTotalCents = 25_000,
                    sourceDeclaredPriceCents = 12_500,
                    resolutionMode = AiEntityResolutionMode.NEW,
                ),
            ),
            totalCents = 25_000,
            initialPaymentCents = 0,
            remainderCents = 25_000,
        )

        composeRule.setContent { AiSalePreviewContent(preview = preview) }

        // El producto y su precio se ven, en el formato que produce el propio composable.
        composeRule.onNodeWithText("Producto nuevo", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText(formatCents(25_000), substring = true).assertCountEquals(2)

        // Y la fila de "Precio habitual" no aparece en el render, según el locale del dispositivo.
        // El prefijo sale del recurso real, así que cubre el caso aunque cambie el monto.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val habitualLabel = context.getString(R.string.ai_sale_preview_new_product_habitual_price, "")
        composeRule.onAllNodesWithText(habitualLabel, substring = true).assertCountEquals(0)
    }
}
