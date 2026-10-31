package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The v7/1.3 bug: the create-customer dialog closed the instant Guardar was tapped, whether or not
 * anything actually got saved. A duplicate name, or `WorkManager` failing to schedule in a release
 * build, both left a blank screen with nothing to explain why the customer never showed up.
 *
 * This pins the contract `CuentivoApp` now enforces: closing the form is not the tap's job, it is
 * `customerSavedToken`'s. `MainActivity` only advances that token after Room and the outbox have
 * both had their turn — which cannot be reproduced here without Android — so what this file checks
 * is the contract itself: a caller that never advances the token leaves the form open with its
 * message, and one that does closes it.
 */
class CustomerCreationFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun a_successful_save_closes_the_form_with_the_trimmed_name() {
        composeRule.setContent {
            Harness(onCreate = { name, _, _ -> created += name; token++ })
        }
        openForm()

        composeRule.onNodeWithText("Nombre del cliente").performTextInput("  Ana López  ")
        composeRule.onNodeWithText("Guardar").performClick()

        composeRule.onAllNodesWithText("Nombre del cliente").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(listOf("Ana López"), created) }
    }

    @Test fun a_failed_save_leaves_the_form_open_and_shows_the_message() {
        // The duplicate-name shape: onCreateCustomer runs, but nothing advances the token — exactly
        // what MainActivity does when the unique index refuses the insert.
        composeRule.setContent {
            Harness(onCreate = { _, _, _ -> message = "Ya tienes otro cliente con ese nombre" })
        }
        openForm()

        composeRule.onNodeWithText("Nombre del cliente").performTextInput("Ana López")
        composeRule.onNodeWithText("Guardar").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Nombre del cliente").assertIsDisplayed()
        composeRule.onNodeWithText("Ya tienes otro cliente con ese nombre").assertIsDisplayed()
    }

    @Test fun saving_disables_guardar_so_a_double_tap_cannot_fire_twice() {
        composeRule.setContent { Harness(isSaving = true) }
        openForm()

        composeRule.onNodeWithText("Nombre del cliente").performTextInput("Ana López")

        composeRule.onNodeWithText("Guardar").assertIsNotEnabled()
    }

    @Test fun not_saving_and_a_valid_name_leave_guardar_enabled() {
        composeRule.setContent { Harness(isSaving = false) }
        openForm()

        composeRule.onNodeWithText("Nombre del cliente").performTextInput("Ana López")

        composeRule.onNodeWithText("Guardar").assertIsEnabled()
    }

    @Test fun form_sections_are_headings_and_fields_advance_via_the_keyboard() {
        composeRule.setContent { Harness() }
        openForm()

        // The long create form groups its fields under section headings TalkBack can navigate by.
        composeRule.onNodeWithText("¿Ya te debía de antes?").assert(isHeading())
        composeRule.onNodeWithText("¿Se llevó algo?").assert(isHeading())
        // And the keyboard moves through it: Next walks name → phone → credit, Done finishes.
        composeRule.onNodeWithText("Nombre del cliente").assert(hasImeAction(ImeAction.Next))
        composeRule.onNodeWithText("Teléfono (opcional)").assert(hasImeAction(ImeAction.Next))
        composeRule.onNodeWithText("¿Hasta cuánto le fías? (opcional)").assert(hasImeAction(ImeAction.Done))
    }

    private fun openForm() {
        composeRule.onNodeWithContentDescription("Clientes").performClick()
        composeRule.onNodeWithContentDescription("Nuevo cliente").performClick()
    }

    private val created = mutableListOf<String>()
    private var token by mutableStateOf(0)
    private var message: String? by mutableStateOf(null)

    @Composable
    private fun Harness(
        onCreate: (String, String?, Long?) -> Unit = { _, _, _ -> },
        // This file is about the create dialog itself; the optional sale has its own test.
        isSaving: Boolean = false,
    ) {
        CuentivoApp(
            state = DashboardUiState.Content("business-1", "Mi negocio", 0, 0, 0, 0, 0),
            onCreateCustomer = { draft -> onCreate(draft.name, draft.phone, draft.creditLimitCents) },
            isSavingCustomer = isSaving,
            customerSavedToken = token,
            message = message,
            onMessageShown = { message = null },
        )
    }
}
