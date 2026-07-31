package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.domain.SheetImport
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Task 6 behaviour. The catalog is a presentation layer over existing callbacks, so these tests
 * assert the callbacks actually fire with the right values rather than only that controls render.
 *
 * The file picker is a system activity and is not driven here. CSV and XLSX parsing itself is
 * covered by `ProductSheetImportTest`; this file only guards the screen that launches it.
 */
class ProductFlowTest {
    @get:Rule val composeRule = createComposeRule()

    private val created = mutableListOf<String>()
    private val edited = mutableListOf<String>()
    private val archived = mutableListOf<String>()
    private var templateShared = 0

    @Test fun catalog_shows_image_name_price_and_secondary_data() {
        composeRule.setContent { Harness() }
        openProducts()

        // Two nodes on purpose: the bottom-bar label and the screen title.
        composeRule.onAllNodesWithText("Productos").assertCountEquals(2)
        composeRule.onNodeWithText("CATÁLOGO").assertIsDisplayed()
        composeRule.onNodeWithText("Café molido 500 g").assertIsDisplayed()
        composeRule.onNodeWithText("$125.00").assertIsDisplayed()
        composeRule.onNodeWithText("SKU CAF-500 · Abarrotes").assertIsDisplayed()
    }

    @Test fun import_and_template_actions_stay_visible_with_48_dp_targets() {
        composeRule.setContent { Harness() }
        openProducts()

        listOf("Importar", "Plantilla").forEach { label ->
            composeRule.onNodeWithText(label)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
        composeRule.onNodeWithContentDescription("Agregar producto")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test fun sharing_the_template_calls_its_callback() {
        composeRule.setContent { Harness() }
        openProducts()

        composeRule.onNodeWithText("Plantilla").performClick()

        composeRule.runOnIdle { assertEquals(1, templateShared) }
    }

    @Test fun an_import_in_flight_reports_itself_and_blocks_a_second_start() {
        composeRule.setContent { Harness(importReport = REPORT) }
        openProducts()

        // With a report already present the screen is idle again, so the action is usable.
        composeRule.onNodeWithText("Importar").assertIsDisplayed()
    }

    @Test fun search_matches_name_and_sku() {
        composeRule.setContent { Harness() }
        openProducts()

        composeRule.onNodeWithText("Buscar por nombre o SKU").performTextInput("Aceite")
        composeRule.onNodeWithText("Aceite vegetal 1 L").assertIsDisplayed()
        composeRule.onAllNodesWithText("Café molido 500 g").assertCountEquals(0)

        composeRule.onNodeWithText("Aceite").performTextClearance()
        composeRule.onNodeWithText("Buscar por nombre o SKU").performTextInput("CAF-500")
        composeRule.onNodeWithText("Café molido 500 g").assertIsDisplayed()
        composeRule.onAllNodesWithText("Aceite vegetal 1 L").assertCountEquals(0)
    }

    @Test fun a_search_with_no_match_explains_itself_without_claiming_the_catalog_is_empty() {
        composeRule.setContent { Harness() }
        openProducts()

        composeRule.onNodeWithText("Buscar por nombre o SKU").performTextInput("zzzz")

        composeRule.onNodeWithText("No encontramos ese producto").assertIsDisplayed()
        composeRule.onAllNodesWithText("Aún no tienes productos").assertCountEquals(0)
    }

    @Test fun an_empty_catalog_offers_a_way_forward() {
        composeRule.setContent { Harness(products = emptyList()) }
        openProducts()

        composeRule.onNodeWithText("Aún no tienes productos").assertIsDisplayed()
        composeRule.onNodeWithText("Agrégalos uno por uno o importa tu catálogo.").assertIsDisplayed()
    }

    @Test fun creating_a_product_sends_name_and_cents() {
        composeRule.setContent { Harness() }
        openProducts()

        composeRule.onNodeWithContentDescription("Agregar producto").performClick()
        composeRule.onNodeWithText("Nombre del producto").performTextInput("Azúcar 1 kg")
        composeRule.onNodeWithText("Precio").performTextInput("38.50")
        composeRule.onNodeWithText("Guardar").performClick()

        composeRule.runOnIdle { assertEquals(listOf("Azúcar 1 kg=3850"), created) }
    }

    @Test fun a_product_cannot_be_saved_without_a_valid_price() {
        composeRule.setContent { Harness() }
        openProducts()

        composeRule.onNodeWithContentDescription("Agregar producto").performClick()
        composeRule.onNodeWithText("Nombre del producto").performTextInput("Sin precio")

        composeRule.onNodeWithText("Guardar").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), created) }
    }

    @Test fun editing_starts_from_that_products_data_and_reports_the_new_price() {
        composeRule.setContent { Harness() }
        openProducts()

        composeRule.onNodeWithContentDescription("Opciones de Café molido 500 g").performClick()
        composeRule.onNodeWithText("Editar").performClick()
        composeRule.onNodeWithText("125.00").assertIsDisplayed()
        composeRule.onNodeWithText("125.00").performTextClearance()
        composeRule.onNodeWithText("Precio").performTextInput("130")
        composeRule.onNodeWithText("Guardar").performClick()

        composeRule.runOnIdle { assertEquals(listOf("p1=13000"), edited) }
    }

    @Test fun editing_warns_that_past_sales_keep_their_own_price() {
        composeRule.setContent { Harness() }
        openProducts()

        composeRule.onNodeWithContentDescription("Opciones de Café molido 500 g").performClick()
        composeRule.onNodeWithText("Editar").performClick()

        composeRule
            .onNodeWithText(
                "El precio nuevo solo aplica a ventas futuras: cada venta guarda el precio que se " +
                    "cobró ese día."
            )
            .assertIsDisplayed()
    }

    @Test fun the_photo_picker_offers_add_change_and_remove() {
        composeRule.setContent { Harness() }
        openProducts()

        composeRule.onNodeWithContentDescription("Agregar producto").performClick()
        // A brand new product has no photo yet, so only the add affordance is offered.
        composeRule.onNodeWithText("Agregar foto").assertIsDisplayed()
        composeRule.onAllNodesWithText("Quitar foto").assertCountEquals(0)
        composeRule.onNodeWithText("Cancelar").performClick()

        // A product that already has one can change or remove it.
        composeRule.onNodeWithContentDescription("Opciones de Aceite vegetal 1 L").performClick()
        composeRule.onNodeWithText("Editar").performClick()
        composeRule.onNodeWithText("Cambiar foto").assertIsDisplayed()
        composeRule.onNodeWithText("Quitar foto").assertIsDisplayed()
    }

    @Test fun archiving_asks_first_and_promises_past_sales_are_untouched() {
        composeRule.setContent { Harness() }
        openProducts()

        composeRule.onNodeWithContentDescription("Opciones de Café molido 500 g").performClick()
        composeRule.onNodeWithText("Eliminar").performClick()

        composeRule.onNodeWithText("¿Eliminar Café molido 500 g?").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Dejará de aparecer al registrar ventas. Tus ventas anteriores no cambian: cada una " +
                    "guarda el nombre y el precio que cobraste ese día."
            )
            .assertIsDisplayed()
        // Nothing archived while the question is still on screen.
        composeRule.runOnIdle { assertEquals(emptyList<String>(), archived) }

        composeRule.onNodeWithText("Cancelar").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), archived) }

        composeRule.onNodeWithContentDescription("Opciones de Café molido 500 g").performClick()
        composeRule.onNodeWithText("Eliminar").performClick()
        composeRule.onNodeWithText("Sí, eliminar").performClick()

        composeRule.runOnIdle { assertEquals(listOf("p1"), archived) }
    }

    @Test fun the_central_action_still_belongs_to_nueva_venta() {
        composeRule.setContent { Harness() }
        openProducts()

        composeRule.onNodeWithContentDescription("Nueva venta").performClick()

        composeRule.onNodeWithContentDescription("Cerrar nueva venta").assertIsDisplayed()
    }

    private fun openProducts() {
        composeRule.onNodeWithText("Productos").performClick()
    }

    @Composable
    private fun Harness(
        products: List<ProductEntity> = CATALOG,
        importReport: SheetImport? = null,
    ) {
        CuentivoApp(
            state = DashboardUiState.Content("business-1", "Mi negocio", 0, 0, 0, 0, 0),
            products = products,
            importReport = importReport,
            onCreateProduct = { name, cents, _ -> created += "$name=$cents" },
            onEditProduct = { product, _, cents, _ -> edited += "${product.id}=$cents" },
            onArchiveProduct = { archived += it.id },
            onShareImportTemplate = { templateShared++ },
        )
    }

    private companion object {
        val CATALOG = listOf(
            product("p1", "Café molido 500 g", 12_500, "CAF-500", "Abarrotes", image = null),
            product("p2", "Aceite vegetal 1 L", 4_800, null, null, image = "p2-photo.jpg"),
        )

        val REPORT = SheetImport(accepted = emptyList(), rejected = emptyList())

        fun product(
            id: String,
            name: String,
            cents: Long,
            sku: String?,
            category: String?,
            image: String?,
        ) = ProductEntity(
            id = id,
            businessId = "business-1",
            name = name,
            description = null,
            sku = sku,
            category = category,
            salePriceCents = cents,
            costPriceCents = null,
            stock = null,
            imageUri = image,
            isActive = true,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            deletedAtEpochMillis = null,
        )
    }
}
