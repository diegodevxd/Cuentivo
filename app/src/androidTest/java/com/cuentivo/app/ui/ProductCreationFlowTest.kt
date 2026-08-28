package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The mirror of `CustomerCreationFlowTest`: product creation went through the exact same
 * insert → enqueue → refresh shape, and had the exact same hole.
 */
class ProductCreationFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun a_successful_save_closes_the_form_with_the_trimmed_name() {
        composeRule.setContent {
            Harness(onCreate = { name, _, _ -> created += name; token++ })
        }
        openForm()

        composeRule.onNodeWithText("Nombre del producto").performTextInput("  Azúcar 1 kg  ")
        composeRule.onNodeWithText("Precio").performTextInput("38.50")
        composeRule.onNodeWithText("Guardar").performClick()

        composeRule.onAllNodesWithText("Nombre del producto").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(listOf("Azúcar 1 kg"), created) }
    }

    @Test fun a_failed_save_leaves_the_form_open_and_shows_the_message() {
        composeRule.setContent {
            Harness(onCreate = { _, _, _ -> message = "No fue posible guardar el producto" })
        }
        openForm()

        composeRule.onNodeWithText("Nombre del producto").performTextInput("Azúcar 1 kg")
        composeRule.onNodeWithText("Precio").performTextInput("38.50")
        composeRule.onNodeWithText("Guardar").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Nombre del producto").assertIsDisplayed()
        composeRule.onNodeWithText("No fue posible guardar el producto").assertIsDisplayed()
    }

    @Test fun saving_disables_guardar_so_a_double_tap_cannot_fire_twice() {
        composeRule.setContent { Harness(isSaving = true) }
        openForm()

        composeRule.onNodeWithText("Nombre del producto").performTextInput("Azúcar 1 kg")
        composeRule.onNodeWithText("Precio").performTextInput("38.50")

        composeRule.onNodeWithText("Guardar").assertIsNotEnabled()
    }

    @Test fun not_saving_and_a_valid_price_leave_guardar_enabled() {
        composeRule.setContent { Harness(isSaving = false) }
        openForm()

        composeRule.onNodeWithText("Nombre del producto").performTextInput("Azúcar 1 kg")
        composeRule.onNodeWithText("Precio").performTextInput("38.50")

        composeRule.onNodeWithText("Guardar").assertIsEnabled()
    }

    private fun openForm() {
        composeRule.onNodeWithContentDescription("Productos").performClick()
        composeRule.onNodeWithContentDescription("Agregar producto").performClick()
    }

    private val created = mutableListOf<String>()
    private var token by mutableStateOf(0)
    private var message: String? by mutableStateOf(null)

    @Composable
    private fun Harness(
        onCreate: (String, Long, String?) -> Unit = { _, _, _ -> },
        isSaving: Boolean = false,
    ) {
        CuentivoApp(
            state = DashboardUiState.Content("business-1", "Mi negocio", 0, 0, 0, 0, 0),
            onCreateProduct = onCreate,
            isSavingProduct = isSaving,
            productSavedToken = token,
            message = message,
            onMessageShown = { message = null },
        )
    }
}
